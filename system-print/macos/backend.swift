import Foundation
import Darwin

// CUPS backend. It never touches Bluetooth: a per-user service owns the printer.
var cancelling: Int32 = 0
signal(SIGTERM) { _ in cancelling = 1 }
signal(SIGINT) { _ in cancelling = 1 }
signal(SIGPIPE, SIG_IGN)
let argv = CommandLine.arguments
if argv.count == 1 {
    print("direct x6driver \"X6/X6h\" \"X6/X6h local service\"")
    exit(0)
}
func fail(_ message: String) -> Never {
    fputs("ERROR: \(message.replacingOccurrences(of: "\n", with: " "))\n", stderr)
    exit(1) // CUPS_BACKEND_FAILED + queue's abort-job policy; durable dedup prevents replay.
}
guard argv.count == 6 || argv.count == 7 else { fail("Invalid backend arguments") }
let uri = ProcessInfo.processInfo.environment["DEVICE_URI"] ?? ""
guard uri.hasPrefix("x6driver:/"), let uid = UInt32(uri.dropFirst("x6driver:/".count)), uid > 0 else { fail("Invalid X6 user URI") }
let path = "/private/tmp/dev.sbelx.x6driver.\(uid)/service.sock"
let limit = 128 * 1024 * 1024
let payload: Data
do {
    if argv.count == 7 {
        let attributes = try FileManager.default.attributesOfItem(atPath: argv[6])
        guard (attributes[.size] as? NSNumber)?.intValue ?? (limit+1) <= limit else { fail("Document exceeds 128 MB") }
        payload = try Data(contentsOf: URL(fileURLWithPath: argv[6]))
    } else {
        var input = Data()
        while let part = try FileHandle.standardInput.read(upToCount: 65536), !part.isEmpty {
            input.append(part); if input.count > limit { fail("Document exceeds 128 MB") }
        }
        payload = input
    }
} catch { fail(error.localizedDescription) }
guard payload.starts(with: Data("%PDF-".utf8)) else { fail("X6 requires the macOS PDF print pipeline; raw printing is unsupported") }

enum IPCError: Error { case unavailable, protocolError(String) }
func transfer(_ fd: Int32, _ bytes: Data) throws {
    try bytes.withUnsafeBytes { raw in
        var pos = 0
        while pos < raw.count {
            let n = Darwin.write(fd, raw.baseAddress!.advanced(by: pos), raw.count-pos)
            if n < 0 && errno == EINTR { continue }
            if n <= 0 { throw IPCError.unavailable }
            pos += n
        }
    }
}
func receive(_ fd: Int32, _ count: Int) throws -> Data {
    var result = Data(count: count)
    try result.withUnsafeMutableBytes { raw in
        var pos = 0
        while pos < count {
            let n = Darwin.read(fd, raw.baseAddress!.advanced(by: pos), count-pos)
            if n < 0 && errno == EINTR { continue }
            if n <= 0 { throw IPCError.unavailable }
            pos += n
        }
    }
    return result
}
func call(_ request: [String: Any], data: Data = Data()) throws -> [String: Any] {
    let fd = socket(AF_UNIX, SOCK_STREAM, 0)
    guard fd >= 0 else { throw IPCError.unavailable }; defer { Darwin.close(fd) }
    var timeout = timeval(tv_sec: 35, tv_usec: 0)
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
    var address = sockaddr_un(); address.sun_family = sa_family_t(AF_UNIX)
    let bytes = path.utf8CString
    guard bytes.count <= MemoryLayout.size(ofValue: address.sun_path) else { throw IPCError.unavailable }
    withUnsafeMutableBytes(of: &address.sun_path) { target in bytes.withUnsafeBytes { source in target.copyBytes(from: source) } }
    let connected = withUnsafePointer(to: &address) { pointer in pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.connect(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }
    guard connected == 0 else { throw IPCError.unavailable }
    // Prevent a different user replacing a stale service socket.
    var peerUID: uid_t = 0, peerGID: gid_t = 0
    guard getpeereid(fd, &peerUID, &peerGID) == 0, peerUID == uid else { throw IPCError.protocolError("Unexpected service owner") }
    var q = request; q["v"] = 1
    let header = try JSONSerialization.data(withJSONObject: q)
    var length = UInt32(header.count).bigEndian, size = UInt64(data.count).bigEndian
    try transfer(fd, withUnsafeBytes(of: &length) { Data($0) }); try transfer(fd, header)
    try transfer(fd, withUnsafeBytes(of: &size) { Data($0) }); if !data.isEmpty { try transfer(fd, data) }
    let prefix = try receive(fd, 4)
    let n = prefix.reduce(0) { ($0 << 8) | Int($1) }
    guard n > 0, n <= 1024 * 1024 else { throw IPCError.protocolError("Invalid service response") }
    guard let response = try JSONSerialization.jsonObject(with: receive(fd, n)) as? [String: Any] else { throw IPCError.protocolError("Invalid service response") }
    if let error = response["error"] as? String { throw IPCError.protocolError(error) }
    return response
}
let options = argv[5]
let jobUUID = options.split(separator: " ").first(where: { $0.hasPrefix("job-uuid=") }).map { String($0.dropFirst(9)) }
let key = "cups:\(uid):\(jobUUID ?? argv[1])"
var jobID: String?
var lastMessage = ""
while true {
    do {
        if cancelling != 0 {
            _ = try call(["op":"cancel-key", "key":key])
            exit(0)
        }
        let result: [String: Any]
        if let id = jobID { result = try call(["op":"job", "id":id]) }
        else {
            result = try call(["op":"submit", "source":"cups", "key":key, "title":argv[3], "copies":Int(argv[4]) ?? 1, "options":options], data:payload)
            jobID = result["id"] as? String
        }
        let state = result["state"] as? String ?? "FAILED"
        let message = result["message"] as? String ?? state
        if message != lastMessage { fputs("INFO: \(message.replacingOccurrences(of:"\n",with:" "))\n",stderr);lastMessage=message }
        if state == "SENT" || state == "COMPLETED" { fputs("STATE: -offline-report\n",stderr);exit(0) }
        if state == "CANCELLED" { exit(0) }
        if state == "FAILED" || state == "INTERRUPTED" { fail(message + "; manual retry required") }
        if state == "WAITING_CONNECTION" { fputs("STATE: +offline-report\n",stderr) }
        else { fputs("STATE: -offline-report\n",stderr) }
    } catch IPCError.protocolError(let message) { fail(message) }
    catch {
        if cancelling != 0 { fail("Service unavailable during cancellation; inspect the job in X6 before retrying") }
        if lastMessage != "Waiting for X6 service" { fputs("INFO: Waiting for X6 service\n",stderr);lastMessage="Waiting for X6 service" }
    }
    usleep(500_000)
}
