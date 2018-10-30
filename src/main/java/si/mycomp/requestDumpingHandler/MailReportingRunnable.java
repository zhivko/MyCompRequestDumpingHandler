package si.mycomp.requestDumpingHandler;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Properties;

import javax.mail.Message;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import io.undertow.UndertowLogger;

public class MailReportingRunnable implements Runnable {

	static String host = "smtp.gmail.com";
	static String mailFrom = "klemen.zivkovic@gmail.com";
	static String pass = "Doitman567";
	static String port = "587";

	static String mailUser = "ts\\zivkovick";

	static Properties props = System.getProperties();
	static ArrayList<String> mailTo = new ArrayList<String>();
	public static ConcurrentArrayList<String> dqlOverDuration = new ConcurrentArrayList<String>();

	public void run() {

		// session.setDebug(true);
		while (true) {
			try {
				if (dqlOverDuration.size() > 0 && !host.trim().equals("") && mailTo.size()>0) {
					Session session = Session.getDefaultInstance(props, null);
					MimeMessage message = new MimeMessage(session);
					message.setFrom(new InternetAddress(mailFrom));
					InternetAddress[] toAddress = new InternetAddress[mailTo.size()];
					// To get the array of addresses
					for (int i = 0; i < mailTo.size(); i++) { // changed from a while loop
						toAddress[i] = new InternetAddress(mailTo.get(i));
					}

					// System.out.println(Message.RecipientType.TO);
					for (int i = 0; i < toAddress.length; i++) {
						message.addRecipient(Message.RecipientType.TO, toAddress[i]);
					}

					InetAddress addr = InetAddress.getLocalHost();
					// Getting IPAddress of localhost - getHostAddress return IP Address
					// in textual format
					String ipAddress = addr.getHostAddress();
					// System.out.println("IP address of localhost from Java Program: " +
					// ipAddress);
					// Hostname
					String hostname = addr.getHostName();

					message.setSubject("Wildfly DFS RequestDumper for " + hostname);

					StringBuilder sb = new StringBuilder();
					// "statusCode#reqStardDate#duration#dql;

					sb.append("statusCode#reqStartDate#uri#duration#timeunit#userName#dql#rObjectId\n");
					while (dqlOverDuration.size() > 0) {
						sb.append(dqlOverDuration.pop(0));
						sb.append(System.lineSeparator());
					}
					message.setText(sb.toString());

					final Session mailSession = Session.getInstance(props, new javax.mail.Authenticator() {
						protected PasswordAuthentication getPasswordAuthentication() {
							return new PasswordAuthentication(mailUser, pass);
						}
					});
					Transport transport = mailSession.getTransport("smtp");
					props.put("mail.smtp.host", host);
					props.put("mail.smtp.port", -1);
					props.put("mail.smtp.debug", "false");
					props.put("mail.smtp.auth", "false");
					transport.connect();
					transport.sendMessage(message, message.getAllRecipients());
					transport.close();

				}
			} catch (Exception e) {
				e.printStackTrace();
				UndertowLogger.REQUEST_LOGGER.error(e.getMessage());
			} finally {
			}
			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
				UndertowLogger.REQUEST_LOGGER.error(e.getMessage());
			}
		}

	}

	public static String getHost() {
		return host;
	}

	public static void setHost(String host) {
		MailReportingRunnable.host = host;
		props.put("mail.smtp.host", host);
	}

	public static String getFrom() {
		return mailFrom;
	}

	public static void setFrom(String from) {
		MailReportingRunnable.mailFrom = from;
	}

	public static String getPass() {
		return pass;
	}

	public static void setMailPass(String pass) {
		MailReportingRunnable.pass = pass;
		props.put("mail.smtp.password", pass);
	}

	public static void setPort(String port) {
		MailReportingRunnable.port = port;
		props.put("mail.smtp.port", port);
	}

	public static void setMailUser(String user) {
		MailReportingRunnable.mailUser = user;
	}

}
