import Foundation
import CoreGraphics
// Deliberately different page widths/marks make page selection and orientation observable.
let target = URL(fileURLWithPath: CommandLine.arguments[1])
var media = CGRect(x: 0, y: 0, width: 159.307, height: 566.929)
guard let context = CGContext(target as CFURL, mediaBox: &media, nil) else { fatalError("Cannot create probe PDF") }
for page in 1...3 {
    context.beginPDFPage(nil)
    context.setFillColor(gray: 1, alpha: 1)
    context.fill(media)
    context.setFillColor(gray: 0, alpha: 1)
    for mark in 0..<page {
        context.fill(CGRect(x: 20 + mark * 35, y: 420, width: 20, height: 80))
    }
    context.endPDFPage()
}
context.closePDF()
