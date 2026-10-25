package dev.gdirect.lite;

import java.lang.reflect.Method;
import java.net.URL;

public final class DirectNetChecks {
    private static int checks;
    private static void expect(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
        checks++;
    }
    public static void main(String[] args) throws Exception {
        String id = "1abcdeFGHIJKLMNOPQRstu0123";
        expect(DirectNet.normalizeSharedLink("https://drive.google.com/file/d/" + id + "/view?usp=sharing")
            .toString().contains("id=" + id), "file/d URL");
        expect(DirectNet.normalizeSharedLink("https://drive.google.com/open?id=" + id)
            .toString().contains("id=" + id), "open?id URL");
        expect("8.8.8.8".equals(DirectNet.parseIp(" 8.8.8.8 ")), "IPv4");
        expect(DirectNet.parseIp("") == null, "empty IP");
        boolean badIp = false;
        try { DirectNet.parseIp("999.1.1.1"); } catch (IllegalArgumentException ex) { badIp = true; }
        expect(badIp, "invalid IP must be rejected");
        expect(DirectNet.googleHost("doc-abcd.googleusercontent.com"), "Google CDN host");
        expect(!DirectNet.googleHost("www.googleapis.com.example.net"), "fake Google host must be rejected");
        boolean badScheme = false;
        try { DirectNet.normalizeSharedLink("http://drive.google.com/file/d/" + id); }
        catch (Exception ex) { badScheme = true; }
        expect(badScheme, "HTTP must be rejected");
        Method confirmation = DirectNet.class.getDeclaredMethod("confirmationPage", URL.class, String.class);
        confirmation.setAccessible(true);
        String page = "<html><form id='download-form' action='https://drive.usercontent.google.com/download'>"
            + "<input type='hidden' name='id' value='" + id + "'>"
            + "<input type='hidden' name='confirm' value='t'></form></html>";
        URL confirm = (URL)confirmation.invoke(null, new URL("https://drive.google.com"), page);
        expect(confirm.toString().contains("id=" + id) && confirm.toString().contains("confirm=t"),
            "confirmation form parsing");
        System.out.println(checks + " local parsing/security checks passed (no network required).");
    }
}
