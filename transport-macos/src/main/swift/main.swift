import Foundation
import CoreBluetooth
import IOBluetooth

// Protocol v1: one JSON object per line; stdout is exclusively protocol data.
func emit(_ event: String, _ body: [String: Any] = [:]) {
    var value = body; value["event"] = event; value["v"] = 1
    if let data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]) {
        FileHandle.standardOutput.write(data); FileHandle.standardOutput.write(Data([10]))
    }
}
final class Bridge: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate, IOBluetoothRFCOMMChannelDelegate {
    var central: CBCentralManager!
    var devices: [String: CBPeripheral] = [:]
    var classic: IOBluetoothDevice?
    var channel: IOBluetoothRFCOMMChannel?
    var active: CBPeripheral?
    var writer: CBCharacteristic?
    var chars: [String: CBCharacteristic] = [:]
    var pendingServices = 0
    var pendingNotify = 0
    var connectID = ""
    var writeQueue: [(String, Data)] = []
    var responseWrite: String?
    var autoScan = false
    var scanGeneration = 0
    let serviceIDs = Set(["AE00", "AE30", "FF00", "AB00"])
    let writeIDs = Set(["AE01", "FF02", "AB01"])
    override init() { super.init(); central = CBCentralManager(delegate: self, queue: .main) }
    func fail(_ id: String, _ message: String) { emit("error", ["id": id, "message": message]) }
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        let states = ["unknown", "resetting", "unsupported", "unauthorized", "poweredOff", "poweredOn"]
        emit("state", ["state": states[central.state.rawValue]])
        if central.state == .poweredOn && autoScan { scan(15) }
    }
    func scan(_ seconds: Int) {
        guard central.state == .poweredOn else { autoScan = true; return }
        for device in IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice] ?? [] {
            emit("device", ["deviceId": device.addressString ?? "", "name": device.name ?? "Paired printer", "rssi": 0, "services": ["1101"], "transport": "spp"])
        }
        autoScan = false; scanGeneration += 1
        let generation = scanGeneration
        central.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
        emit("scanning", ["active": true])
        DispatchQueue.main.asyncAfter(deadline: .now() + .seconds(seconds)) {
            if self.scanGeneration == generation { self.central.stopScan(); emit("scanning", ["active": false]) }
        }
    }
    func centralManager(_ central: CBCentralManager, didDiscover p: CBPeripheral, advertisementData a: [String: Any], rssi: NSNumber) {
        let id = p.identifier.uuidString; devices[id] = p
        emit("device", ["deviceId": id, "name": a[CBAdvertisementDataLocalNameKey] as? String ?? p.name ?? "Unknown", "rssi": rssi,
            "services": (a[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? []).map { $0.uuidString },
            "manufacturerData": (a[CBAdvertisementDataManufacturerDataKey] as? Data ?? Data()).base64EncodedString(), "transport": "ble"])
    }
    func centralManager(_ central: CBCentralManager, didConnect p: CBPeripheral) { p.delegate = self; p.discoverServices(nil) }
    func centralManager(_ central: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) { fail(connectID, error?.localizedDescription ?? "Connection failed") }
    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        writer = nil; active = nil
        for (id, _) in writeQueue { fail(id, "Disconnected") }; writeQueue.removeAll()
        if let id = responseWrite { fail(id, "Disconnected"); responseWrite = nil }
        emit("disconnected", ["message": error?.localizedDescription ?? "Disconnected"])
    }
    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        if let e = error { fail(connectID, e.localizedDescription); return }
        let services = p.services ?? []; pendingServices = services.count
        emit("services", ["services": services.map { $0.uuid.uuidString }])
        if services.isEmpty { fail(connectID, "No services"); return }
        for s in services { p.discoverCharacteristics(nil, for: s) }
    }
    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor s: CBService, error: Error?) {
        if let e = error { fail(connectID, e.localizedDescription); return }
        for c in s.characteristics ?? [] {
            chars[c.uuid.uuidString] = c
            if serviceIDs.contains(s.uuid.uuidString) && writeIDs.contains(c.uuid.uuidString) && (c.properties.contains(.write) || c.properties.contains(.writeWithoutResponse)) { writer = c }
            if serviceIDs.contains(s.uuid.uuidString) && (c.properties.contains(.notify) || c.properties.contains(.indicate)) {
                pendingNotify += 1; p.setNotifyValue(true, for: c)
            }
            emit("characteristic", ["service": s.uuid.uuidString, "uuid": c.uuid.uuidString, "properties": c.properties.rawValue])
        }
        pendingServices -= 1; ready()
    }
    func peripheral(_ p: CBPeripheral, didUpdateNotificationStateFor c: CBCharacteristic, error: Error?) {
        pendingNotify = max(0, pendingNotify - 1)
        if let e = error { fail(connectID, e.localizedDescription); return }; ready()
    }
    func ready() {
        guard pendingServices == 0 && pendingNotify == 0, let p = active else { return }
        guard let c = writer else { fail(connectID, "No supported printer write characteristic"); return }
        let type: CBCharacteristicWriteType = c.properties.contains(.writeWithoutResponse) ? .withoutResponse : .withResponse
        emit("connected", ["id": connectID, "deviceId": p.identifier.uuidString, "name": p.name ?? "Printer", "maxWrite": p.maximumWriteValueLength(for: type), "writer": c.uuid.uuidString])
    }
    func peripheral(_ p: CBPeripheral, didUpdateValueFor c: CBCharacteristic, error: Error?) {
        if let e = error { emit("error", ["message": e.localizedDescription]); return }
        emit("notification", ["uuid": c.uuid.uuidString, "data": (c.value ?? Data()).base64EncodedString()])
    }
    func peripheralIsReady(toSendWriteWithoutResponse p: CBPeripheral) { drain() }
    func peripheral(_ p: CBPeripheral, didWriteValueFor c: CBCharacteristic, error: Error?) {
        if let id = responseWrite {
            responseWrite = nil
            if let e = error { fail(id, e.localizedDescription) } else { emit("ack", ["id": id]) }
        }; drain()
    }
    func drain() {
        guard let p = active, let c = writer, responseWrite == nil else { return }
        let noResponse = c.properties.contains(.writeWithoutResponse)
        while !writeQueue.isEmpty {
            if noResponse && !p.canSendWriteWithoutResponse { return }
            let (id, data) = writeQueue.removeFirst()
            p.writeValue(data, for: c, type: noResponse ? .withoutResponse : .withResponse)
            if noResponse { emit("ack", ["id": id]) } else { responseWrite = id; return }
        }
    }
    @objc func sdpQueryComplete(_ device: IOBluetoothDevice, status: IOReturn) {
        guard classic === device else { return }
        guard status == kIOReturnSuccess, let record = device.getServiceRecord(for: IOBluetoothSDPUUID.uuid16(0x1101)) else { fail(connectID, "No serial port service"); classic = nil; return }
        var channelID: BluetoothRFCOMMChannelID = 0
        guard record.getRFCOMMChannelID(&channelID) == kIOReturnSuccess else { fail(connectID, "No RFCOMM channel"); classic = nil; return }
        let result = device.openRFCOMMChannelAsync(&channel, withChannelID: channelID, delegate: self)
        if result != kIOReturnSuccess { fail(connectID, "RFCOMM open failed: \(result)"); classic = nil }
    }
    func rfcommChannelOpenComplete(_ ch: IOBluetoothRFCOMMChannel!, status: IOReturn) {
        guard status == kIOReturnSuccess, let device = classic else { fail(connectID, "RFCOMM connection failed"); channel = nil; classic = nil; return }
        channel = ch
        emit("connected", ["id": connectID, "deviceId": device.addressString ?? "", "name": device.name ?? "SPP printer", "maxWrite": Int(ch.getMTU()), "writer": "RFCOMM"])
    }
    func rfcommChannelData(_ ch: IOBluetoothRFCOMMChannel!, data dataPointer: UnsafeMutableRawPointer!, length dataLength: Int) {
        emit("notification", ["uuid": "RFCOMM", "data": Data(bytes: dataPointer, count: dataLength).base64EncodedString()])
    }
    func rfcommChannelClosed(_ ch: IOBluetoothRFCOMMChannel!) {
        channel = nil; classic = nil; emit("disconnected", ["message": "RFCOMM disconnected"])
    }
    func command(_ cmd: [String: Any]) {
        let id = cmd["id"] as? String ?? ""
        guard (cmd["v"] as? Int) == 1 else { fail(id, "Unsupported protocol version"); return }
        switch cmd["op"] as? String ?? "" {
        case "connectSPP":
            guard active == nil && classic == nil else { fail(id, "Disconnect current printer first"); return }
            let key = cmd["deviceId"] as? String ?? ""
            guard let device = (IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice] ?? []).first(where: { $0.addressString == key }) else { fail(id, "Pair the printer in macOS Bluetooth settings first"); return }
            classic = device; connectID = id
            let result = device.performSDPQuery(self)
            if result != kIOReturnSuccess { classic = nil; fail(id, "SDP query failed: \(result)") }
            DispatchQueue.main.asyncAfter(deadline: .now() + 20) { if self.connectID == id && self.channel == nil { self.classic?.closeConnection(); self.classic = nil; self.fail(id, "SPP connection timed out") } }
        case "scan": scan(max(1, min(30, cmd["seconds"] as? Int ?? 15))); emit("ack", ["id": id])
        case "stopScan": central.stopScan(); scanGeneration += 1; emit("ack", ["id": id])
        case "connect":
            guard central.state == .poweredOn else { fail(id, "Bluetooth is not powered on"); return }
            guard active == nil && classic == nil else { fail(id, "Disconnect current printer first"); return }
            let key = cmd["deviceId"] as? String ?? ""
            if devices[key] == nil, let uuid = UUID(uuidString: key) { devices[key] = central.retrievePeripherals(withIdentifiers: [uuid]).first }
            guard let p = devices[key] else { fail(id, "Device not found; scan again"); return }
            central.stopScan(); connectID = id; active = p; writer = nil; chars.removeAll(); pendingNotify = 0
            central.connect(p)
            DispatchQueue.main.asyncAfter(deadline: .now() + 20) { if self.connectID == id && self.writer == nil { self.central.cancelPeripheralConnection(p); self.fail(id, "Connection timed out") } }
        case "write":
            if let ch = channel {
                guard let encoded = cmd["data"] as? String, var bytes = Data(base64Encoded: encoded), bytes.count > 0, bytes.count <= Int(ch.getMTU()) else { fail(id, "Invalid SPP data"); return }
                let count = UInt16(bytes.count)
                let result = bytes.withUnsafeMutableBytes { ch.writeSync($0.baseAddress, length: count) }
                if result == kIOReturnSuccess { emit("ack", ["id": id]) } else { fail(id, "SPP write failed: \(result)") }; return
            }
            guard let p = active, let c = writer else { fail(id, "Not connected"); return }
            guard let s = cmd["data"] as? String, let data = Data(base64Encoded: s), !data.isEmpty else { fail(id, "Invalid data"); return }
            let type: CBCharacteristicWriteType = c.properties.contains(.writeWithoutResponse) ? .withoutResponse : .withResponse
            guard data.count <= p.maximumWriteValueLength(for: type), writeQueue.count < 64 else { fail(id, "Write too large or queue full"); return }
            writeQueue.append((id, data)); drain()
        case "disconnect":
            channel?.close(); channel = nil; classic?.closeConnection(); classic = nil
            if let p = active { central.cancelPeripheralConnection(p) }
            emit("ack", ["id": id])
        case "quit": channel?.close(); classic?.closeConnection(); if let p = active { central.cancelPeripheralConnection(p) }; exit(0)
        case "ping": emit("ack", ["id": id])
        default: fail(id, "Unknown operation")
        }
    }
}
let bridge = Bridge()
if CommandLine.arguments.contains("--scan") { bridge.autoScan = true; DispatchQueue.main.asyncAfter(deadline: .now() + 25) { exit(0) } }
else {
    DispatchQueue.global(qos: .userInitiated).async {
        while let line = readLine() {
            if let data = line.data(using: .utf8), let cmd = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
                DispatchQueue.main.async { bridge.command(cmd) }
            } else { DispatchQueue.main.async { emit("error", ["message": "Invalid JSON"]) } }
        }
        DispatchQueue.main.async { exit(0) }
    }
}
RunLoop.main.run()
