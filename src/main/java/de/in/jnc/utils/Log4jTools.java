package de.in.jnc.utils;

import java.io.PrintStream;
import java.net.InetAddress;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.appender.SocketAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import org.apache.logging.log4j.core.layout.SerializedLayout;
import org.apache.logging.log4j.core.net.Protocol;
import org.apache.logging.log4j.core.net.TcpSocketManager;

public class Log4jTools {
	public static void redirectStdOutErrLog() {
		String logLevel = System.getenv("OSSD");
		if (logLevel == null) {
			System.setOut(createLoggingProxy(System.out));
			System.setErr(createLoggingProxy(System.err));
		} else {
			String logPort = System.getenv("OSSD.port");
			int port = 4445;
			if (StringUtils.isNotBlank(logPort) && logPort.matches("^([1-9][0-9]{0,3}|[1-5][0-9]{4}|6[0-4][0-9]{3}|65[0-4][0-9]{2}|655[0-2][0-9]|6553[0-5])$"))
				try {
					port = Integer.parseInt(logPort);
				} catch (Exception ex) {
					// no logging
				}
			String logHost = System.getenv("OSSD.host");
			String host = StringUtils.isBlank(logHost) ? "127.0.0.1" : logHost;
			@SuppressWarnings("deprecation")
			SocketAppender appender = SocketAppender.newBuilder().setConfiguration(new DefaultConfiguration()).setHost(host).setIgnoreExceptions(false)
					.setLayout(SerializedLayout.createLayout()).setName("Debug-Appender").setPort(port).setProtocol(Protocol.TCP).build();
			TcpSocketManager tcpSocketManager = (TcpSocketManager) appender.getManager();
			if (tcpSocketManager.getSocket() != null && tcpSocketManager.getSocket().isConnected()) {
				appender.start();
				Level level = "2".equals(logLevel) ? Level.DEBUG : "3".equals(logLevel) ? Level.TRACE : Level.INFO;
				((org.apache.logging.log4j.core.Logger) LogManager.getLogger(Log4jTools.class)).get().addAppender(appender, level, null);
				Configurator.setRootLevel(level);
			}
		}
	}

	public static PrintStream createLoggingProxy(PrintStream realPrintStream) {
		return new PrintStream(realPrintStream) {
			@Override
			public void print(String string) {
				realPrintStream.print(string);
				LogManager.getLogger("console").info(string);
			}
		};
	}

	public static void logEnvironment(Logger log) {
		StringBuilder sb = new StringBuilder();
		String ls = System.lineSeparator();
		sb.append(ls);
		sb.append("==========================================================================================================").append(ls);
		sb.append("host / user      : ").append(createHostAndUser()).append(ls);
		sb.append("operating system : name = ").append(System.getProperty("os.name"));
		sb.append(" / version = ").append(System.getProperty("os.version"));
		sb.append(" / architecture = ").append(System.getProperty("os.arch")).append(ls);
		sb.append("java runtime     : vendor = ").append(System.getProperty("java.vendor"));
		sb.append(" / version = ").append(System.getProperty("java.runtime.version")).append(ls);
		sb.append("java home        : ").append(System.getProperty("java.home")).append(ls);
		sb.append("library path     : ").append(System.getProperty("java.library.path")).append(ls);
		sb.append("class path       : ").append(System.getProperty("java.class.path")).append(ls);
		sb.append("==========================================================================================================");

		if (log.isTraceEnabled())
			log.trace(sb.toString());
		else if (log.isDebugEnabled())
			log.debug(sb.toString());
		else
			log.info(sb.toString());
	}

	private static String createHostAndUser() {
		try {
			String property = System.getProperty("os.name");
			if (property != null) {
				if (property.toLowerCase().contains("windows"))
					return System.getenv().get("COMPUTERNAME") + " / " + System.getenv().get("USERNAME");
				return InetAddress.getLocalHost().getHostName() + " / " + System.getenv().get("LOGNAME");
			}
		} catch (Exception ex) {
		}
		return "";
	}

}
