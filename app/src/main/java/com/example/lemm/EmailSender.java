package com.example.lemm;

import android.util.Log;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.mail.Message;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

public class EmailSender {
    private static final String SMTP_HOST = "smtp.gmail.com";
    private static final String SMTP_PORT = "465";

    // Credentials are injected at build time from local.properties (MAIL_USER / MAIL_APP_PASSWORD),
    // so the SMTP app-password never lives in version-controlled source.
    private static final String APP_EMAIL = BuildConfig.MAIL_USER;
    private static final String APP_PASSWORD = BuildConfig.MAIL_APP_PASSWORD.trim();

    // THE BEAUTIFUL HTML TEMPLATE
    public static void sendOfficialEmail(String toEmail, String subject, String headline, String bodyText) {
        String htmlBody = "<div style=\"font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; max-width: 600px; margin: 0 auto; border: 1px solid #E0E0E0; border-radius: 10px; overflow: hidden; box-shadow: 0 4px 10px rgba(0,0,0,0.05);\">" +
                "<div style=\"background-color: #0C3D6A; padding: 25px; text-align: center;\">" +
                "<h1 style=\"color: #FFFFFF; margin: 0; font-size: 28px; letter-spacing: 1px;\">Lemma</h1>" + // BRANDING FIXED
                "<p style=\"color: #90CAF9; margin: 5px 0 0 0; font-size: 14px;\">Smart Geometry Solver</p>" +
                "</div>" +
                "<div style=\"padding: 30px; background-color: #FFFFFF; color: #333333; line-height: 1.6; font-size: 16px;\">" +
                "<h2 style=\"color: #0C3D6A; margin-top: 0;\">" + headline + "</h2>" +
                "<p>" + bodyText.replace("\n", "<br>") + "</p>" +
                "<br>" +
                "<hr style=\"border: none; border-top: 1px solid #EEEEEE; margin: 20px 0;\">" +
                "<p style=\"font-size: 13px; color: #888888; margin: 0;\">This is an automated security message from Lemma. Please do not reply to this email.</p>" +
                "</div>" +
                "</div>";

        executeEmail(toEmail, subject, htmlBody);
    }

    // FIX: Make the old method public again, but automatically upgrade it to HTML!
    // Now you don't have to change the Google Login code at all!
    public static void sendEmail(String toEmail, String subject, String bodyText) {
        sendOfficialEmail(toEmail, subject, subject, bodyText);
    }

    // The actual background sender
    private static void executeEmail(String toEmail, String subject, String htmlBody) {
        if (APP_EMAIL == null || APP_EMAIL.isEmpty() || APP_PASSWORD == null || APP_PASSWORD.isEmpty()) {
            Log.e("EmailSender", "Email credentials not configured (set MAIL_USER / MAIL_APP_PASSWORD in local.properties).");
            return;
        }
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                Properties props = new Properties();
                props.put("mail.smtp.host", SMTP_HOST);
                props.put("mail.smtp.socketFactory.port", SMTP_PORT);
                props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
                props.put("mail.smtp.auth", "true");
                props.put("mail.smtp.port", SMTP_PORT);

                Session session = Session.getInstance(props, new javax.mail.Authenticator() {
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(APP_EMAIL, APP_PASSWORD);
                    }
                });

                Message message = new MimeMessage(session);
                message.setFrom(new InternetAddress(APP_EMAIL, "Lemma Support"));
                message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail));
                message.setSubject(subject);

                message.setContent(htmlBody, "text/html; charset=utf-8");

                Transport.send(message);
                Log.d("EmailSender", "✅ SUCCESS! HTML Email sent to " + toEmail);

            } catch (Exception e) {
                Log.e("EmailSender", "❌ CRITICAL ERROR IN EMAILSENDER: ", e);
            }
        });
    }
}