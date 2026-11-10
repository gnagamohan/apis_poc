package auth;

import com.typesafe.config.Config;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Properties;

/** Plain-text email over SMTP (blocking: call from {@link DatabaseExecutionContext}). */
@Singleton
public class Mailer {

    private final Session session;
    private final InternetAddress from;

    @Inject
    public Mailer(Config config) {
        Properties props = new Properties();
        props.put("mail.smtp.host", config.getString("mail.smtp.host"));
        props.put("mail.smtp.port", String.valueOf(config.getInt("mail.smtp.port")));
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout", "5000");
        props.put("mail.smtp.writetimeout", "5000");
        this.session = Session.getInstance(props);
        try {
            this.from = new InternetAddress(config.getString("mail.from"));
        } catch (AddressException e) {
            throw new IllegalStateException("mail.from is not a valid address", e);
        }
    }

    public void send(String to, String subject, String text) throws MessagingException {
        MimeMessage message = new MimeMessage(session);
        message.setFrom(from);
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        message.setSubject(subject, "UTF-8");
        message.setText(text, "UTF-8");
        Transport.send(message);
    }
}
