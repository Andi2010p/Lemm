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
    // ⚠️ REPLACE THESE WITH YOUR ACTUAL DETAILS ⚠️
    private static final String SMTP_HOST = "smtp.gmail.com";
    private static final String SMTP_PORT = "587";

    // Put the email address you want the app to send FROM
    private static final String APP_EMAIL = "LemmaOfficial13@gmail.com";

    // Put the 16-character Google APP PASSWORD here (NOT your normal password)
    private static final String APP_PASSWORD = "qahl nues yxkz vtox\n";

    public static void sendEmail(String toEmail, String subject, String body) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                Properties props = new Properties();
                props.put("mail.smtp.auth", "true");
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.host", SMTP_HOST);
                props.put("mail.smtp.port", SMTP_PORT);

                Session session = Session.getInstance(props, new javax.mail.Authenticator() {
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(APP_EMAIL, APP_PASSWORD);
                    }
                });

                Message message = new MimeMessage(session);
                message.setFrom(new InternetAddress(APP_EMAIL));
                message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail));
                message.setSubject(subject);
                message.setText(body);

                Transport.send(message);
                Log.d("EmailSender", "✅ Email sent successfully to " + toEmail);
            } catch (Exception e) {
                Log.e("EmailSender", "❌ Failed to send email to " + toEmail, e);
            }
        });
    }
}