import Foundation
import CoreGraphics
import CoreText
let directory=URL(fileURLWithPath:CommandLine.arguments[1],isDirectory:true)
for (name,width,height) in [("portrait",120.0,170.0),("landscape",170.0,100.0)] {
    var box=CGRect(x:0,y:0,width:width+20,height:height+20)
    let context=CGContext(directory.appendingPathComponent("photo-\(name).pdf") as CFURL,mediaBox:&box,nil)!
    context.beginPDFPage(nil);context.setFillColor(gray:1,alpha:1);context.fill(box)
    for step in 0..<32 {context.setFillColor(gray:CGFloat(step)/31,alpha:1);context.fill(CGRect(x:10+width*Double(step)/32,y:10,width:width/32+0.1,height:height-25))}
    context.setFillColor(gray:0.15,alpha:1);context.fillEllipse(in:CGRect(x:30,y:40,width:40,height:40))
    context.setFillColor(gray:0.8,alpha:1);context.fillEllipse(in:CGRect(x:38,y:48,width:24,height:24))
    let attributes:[NSAttributedString.Key:Any]=[NSAttributedString.Key(kCTFontAttributeName as String):CTFontCreateWithName("Helvetica" as CFString,9,nil),NSAttributedString.Key(kCTForegroundColorAttributeName as String):CGColor(gray:0,alpha:1)]
    let label=NSAttributedString(string:"X6 - \(name)",attributes:attributes)
    context.textPosition=CGPoint(x:10,y:height);CTLineDraw(CTLineCreateWithAttributedString(label),context)
    context.endPDFPage();context.closePDF()
}
