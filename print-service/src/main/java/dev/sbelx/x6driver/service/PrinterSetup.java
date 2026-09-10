package dev.sbelx.x6driver.service;

import com.google.gson.*;
import java.io.*;
import java.util.*;

/** Interactive installation step; all Bluetooth operations belong to the service. */
final class PrinterSetup {
    static List<JsonObject> candidates(JsonArray devices) {
        var unique = new LinkedHashMap<String, JsonObject>();
        for (var item : devices) {
            var device = item.getAsJsonObject();
            String name = device.get("name").getAsString();
            if (name.matches("(?i)^X6h?(?:$|[-_\\s].*)"))
                unique.putIfAbsent(device.get("id").getAsString(), device);
        }
        return new ArrayList<>(unique.values());
    }
    static int choice(String answer, int count) {
        try { int n = Integer.parseInt(answer.strip()); return n >= 1 && n <= count ? n - 1 : -1; }
        catch (NumberFormatException e) { return -1; }
    }
    static void run() throws Exception {
        System.out.println("Turn on your X6/X6h printer and allow Bluetooth access if macOS asks.");
        Exception last = null;
        boolean ready = false;
        for (int i = 0; i < 30; i++) {
            try { Wire.call(Wire.request("status"), null); ready = true; break; }
            catch (Exception e) { last = e; Thread.sleep(1000); }
        }
        if (!ready) throw new IOException("Service did not start. Run the installer again.", last);
        var input = new BufferedReader(new InputStreamReader(System.in));
        while (true) {
            System.out.println("Searching for printers (15 seconds)…");
            Wire.call(Wire.request("scan"), null);
            Thread.sleep(16000); // Collect the complete scan, not just the first advertisement.
            var found = candidates(Wire.call(Wire.request("status"), null).getAsJsonArray("devices"));
            if (found.isEmpty()) {
                System.out.print("No X6/X6h found. Check power and Bluetooth permission. Press Enter to retry, or type q to finish setup later: ");
                String answer = input.readLine();
                if (answer == null || answer.strip().equalsIgnoreCase("q"))
                    throw new IOException("Driver installed, but no printer selected. Run the installer again with the printer on.");
                continue;
            }
            int selected = 0;
            if (found.size() > 1) {
                System.out.println("Choose a printer:");
                for (int i = 0; i < found.size(); i++) {
                    var d = found.get(i);
                    System.out.printf("%d. %s (%s)%n", i + 1, d.get("name").getAsString(), d.get("id").getAsString());
                }
                do {
                    System.out.print("Printer number: ");
                    String answer = input.readLine();
                    if (answer == null) throw new IOException("Printer selection requires a terminal. Run the installer again.");
                    selected = choice(answer, found.size());
                } while (selected < 0);
            }
            var device = found.get(selected);
            var request = Wire.request("connect"); request.add("device", device);
            Wire.call(request, null);
            System.out.println("Connected to " + device.get("name").getAsString() + ". Ready to print using Command-P.");
            return;
        }
    }
}
