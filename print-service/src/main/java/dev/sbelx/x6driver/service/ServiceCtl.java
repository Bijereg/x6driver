package dev.sbelx.x6driver.service;
import dev.sbelx.x6driver.transport.PrinterTransport;
import java.util.List;
public final class ServiceCtl {
    public static void main(String[] args)throws Exception{
        if(args.length==0)throw new IllegalArgumentException("status | scan | connect ID NAME | cancel ID");
        var q=Wire.request(args[0]);
        if(args[0].equals("connect")){if(args.length!=3)throw new IllegalArgumentException("connect ID NAME");q.add("device",Wire.JSON.toJsonTree(new PrinterTransport.Device(args[1],args[2],0,List.of("AE30"),"ble")));}
        if(args[0].equals("cancel")){q.addProperty("id",args[1]);}
        System.out.println(Wire.call(q,null));
    }
}
