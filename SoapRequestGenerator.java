/*
    Copyright (c) 2008-2016 Ariba, Inc.
    All rights reserved. Patents pending.

    $Id$

    Responsible: WFender
*/

package test.ariba.channel.ws.framework;

import ariba.util.security.Security;
import org.apache.commons.httpclient.util.EncodingUtil;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import ariba.util.core.Fmt;
import ariba.util.core.MIME;
import ariba.channel.ws.Constants;
import ariba.util.core.StringUtil;

import java.nio.ByteBuffer;
import java.util.logging.ConsoleHandler;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import java.io.*;
import java.util.Map;
import java.util.UUID;
// This is the holder for the soap request state.
// It also is the interface for JUnit tests to generate
// SOAP requests against the server.

public class SoapRequestGenerator {
	private String m_host = null;
	private String m_port = null;
	private String m_realm = null;
	private String m_event = null;
	private String m_input = null;
	private String m_app = null;
	private String m_userpass = null;
	private boolean m_secure = false;

	protected String getUrlString() {
		StringBuffer urlString = new StringBuffer();
		if (isSecure()) {
			urlString.append("https://");
		} else {
			urlString.append("http://");
		}
		urlString.append(getHost());
		if (getPort() != null) {
			urlString.append(':');
			urlString.append(getPort());
		}
		urlString.append("/");
		urlString.append(getApp());
		urlString.append("/soap/");
		urlString.append(getRealm());
		urlString.append('/');
		urlString.append(getEvent());

		return urlString.toString();
	}

	private static TrustManager[] trustAllCerts = new TrustManager[] { new X509TrustManager() {
		public java.security.cert.X509Certificate[] getAcceptedIssuers() {
			return null;
		}

		public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {
		}

		public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {
		}
	} };

	public void submitMtomRequest(PrintStream output, String mtomContentType) {
		DataInputStream response = null;
		DataOutputStream request = null;
		File inputFile = null;
		DataInputStream soapRequest = null;
		String line = null;
		byte[] buffer = new byte[1024];
		int size = 0;
		HttpURLConnection soapConn = null;

		try {
			SSLContext sslCtx = Security.getSSLContext();
			sslCtx.init(null, getTrustAllCerts(), new java.security.SecureRandom());
			HttpsURLConnection.setDefaultSSLSocketFactory(sslCtx.getSocketFactory());

			URL wsdlUrl = new URL(getUrlString());
			URLConnection urlConn = wsdlUrl.openConnection();
			if (!(urlConn instanceof HttpURLConnection)) {
				output.println("Connection not a valid http connection");
				return;
			}
			soapConn = (HttpURLConnection) urlConn;
			soapConn.setRequestMethod("POST");
			// String contentTypeString =
			// 36 "multipart/related; boundary=2342342; start=main-content-id";
			// multipart/related;boundary="----=_Part_695_858273167.1600379616007";type="application/xop+xml"
			if (!StringUtil.nullOrEmptyOrBlankString(mtomContentType)) {
				soapConn.setRequestProperty(MIME.HeaderContentType, mtomContentType);
			} else {
				soapConn.setRequestProperty(MIME.HeaderContentType, Constants.UTF8ContentTypeDeclaration);
			}

			if (getUsernamePassword() != null) {
				byte[] byteForm = EncodingUtil.getBytes(getUsernamePassword(), "ISO-8859-1");
				StringBuffer value = new StringBuffer("Basic ");
				value.append(EncodingUtil.getAsciiString(byteForm));
				soapConn.setRequestProperty("authorization", value.toString());
			}

			soapConn.setDoOutput(true);
			inputFile = new File(getInput());
			soapRequest = new DataInputStream(new FileInputStream(inputFile));

			request = new DataOutputStream(soapConn.getOutputStream());
			while (true) {
				size = soapRequest.read(buffer);
				if (size < 0) {
					break;
				}
				request.write(buffer, 0, size);
			}

			if (soapConn.getResponseCode() == 200) {
				response = new DataInputStream(soapConn.getInputStream());
			} else {
				output.println("Got an error response of " + soapConn.getResponseCode());
				response = new DataInputStream(soapConn.getErrorStream());
			}
			while (true) {
				line = response.readLine();
				if (line == null) {
					break;
				}
				output.println(line);
			}
		} catch (Exception e) {
			System.err.println(e);
			e.printStackTrace(System.err);
		} finally {
			try {
				if (request != null) {
					request.close();
					request = null;
				}
				if (soapRequest != null) {
					soapRequest.close();
					soapRequest = null;
				}
				if (response != null) {
					response.close();
					response = null;
				}
			} catch (Exception e) {
				System.err.println(e);
			}
		}
	}

	public void submitMtomRequestwithAttachments(PrintStream output, String mtomContentType,
												 Map<File, Map<String, String>> Attachments) {
		DataInputStream response = null;
		DataOutputStream request = null;
		File inputFile = null;
		DataInputStream soapRequest = null;
		String line = null;
		byte[] buffer = new byte[1024];
		int size = 0;
		HttpURLConnection soapConn = null;
		String Boundary = UUID.randomUUID().toString();
		byte[] b = new byte[1024];
		ByteBuffer attachmentsBuff = ByteBuffer.wrap(b);
		ByteBuffer soapRequestBuff = ByteBuffer.wrap(buffer);

		try {
			SSLContext sslCtx = Security.getSSLContext();
			sslCtx.init(null, getTrustAllCerts(), new java.security.SecureRandom());
			HttpsURLConnection.setDefaultSSLSocketFactory(sslCtx.getSocketFactory());

			URL wsdlUrl = new URL(getUrlString());
			URLConnection urlConn = wsdlUrl.openConnection();
			if (!(urlConn instanceof HttpURLConnection)) {
				output.println("Connection not a valid http connection");
				return;
			}
			soapConn = (HttpURLConnection) urlConn;
			soapConn.setRequestMethod("POST");
			// Populate request headers
			soapConn.setRequestProperty("Connection", "Keep-Alive");
			soapConn.setRequestProperty("Cache-Control", "no-cache");
			// soapConn.setRequestProperty("Accept-Encoding", "gzip,deflate");
			soapConn.setRequestProperty("Content-Type",
				"multipart/related; type=\"application/xop+xml\"; start=\"<rootpart@soapui.org>\";"
					+ " start-info=\"text/xml\"; boundary=" + "\"" + Boundary + "\"");

			if (getUsernamePassword() != null) {
				byte[] byteForm = EncodingUtil.getBytes(getUsernamePassword(), "ISO-8859-1");
				StringBuffer value = new StringBuffer("Basic ");
				value.append(EncodingUtil.getAsciiString(byteForm));
				soapConn.setRequestProperty("authorization", value.toString());
			}

			soapConn.setDoOutput(true);
			inputFile = new File(getInput());
			soapRequest = new DataInputStream(new FileInputStream(inputFile));

			request = new DataOutputStream(soapConn.getOutputStream());

			System.out.println("1request size before flushing the file-------" + request.size());

			request.writeBytes("\n--" + Boundary + "BEGINING123" + System.currentTimeMillis() + "\n"
				+ "Content-Type: application/xop+xml; charset=UTF-8; type=\"text/xml\"\n"
				+ "Content-Transfer-Encoding: 8bit\n" + "Content-ID: <rootpart@soapui.org>" + "\n\n");

			System.out.println("2request size after flushing the file-------" + request.size());

			while (true) {
				size = soapRequest.read(buffer);
				if (size < 0) {
					break;
				}
				request.write(buffer, 0, size);
			}
			request.flush();
			System.out.println("3request size after flushing the file-------" + request.size());

			// Populate attachment parts in request body
			if (!(Attachments.entrySet() == null)) {
				for (Map.Entry attachment : Attachments.entrySet()) {
					attachment.getKey();

					Map<String, String> attachmentInfo = (Map<String, String>) attachment.getValue();
					;
					request.writeBytes("\n--" + Boundary + "STARTATT123" + System.currentTimeMillis() + "\n"
						+ "Content-Type: " + attachmentInfo.get("Content-Type") + "; name=" + "\""
						+ attachmentInfo.get("filename") + "\"" + "\n" + "Content-Transfer-Encoding: binary\n"
						+ "Content-ID: " + attachmentInfo.get("Content-ID") + "\n"
						+ "Content-Disposition: attachment; name=" + "\"" + attachmentInfo.get("filename") + "\""
						+ "; filename=" + "\"" + attachmentInfo.get("filename") + "\"" + "\n\n");

					File attFile = (File) attachment.getKey();
					InputStream in = new FileInputStream(attFile);
//				byte[] b = new byte[1024];
					int l = 0;
					while ((l = in.read(b)) != -1)
						request.write(b, 0, l); // Write to file
//				request.writeBytes("\n\n--" + Boundary + "--\n");
//New				request.flush();

				}
				request.writeBytes("\n\n--" + Boundary + "ENDATT123" + System.currentTimeMillis() + "--\n");
				request.flush();
			} else {
				System.out.println(
					"====================################ No attachment are present to be added in the request");
			}

			System.out.println("====================################" + "SOAP Request message1------");

			if (soapConn.getResponseCode() == 200) {
				response = new DataInputStream(soapConn.getInputStream());
			} else {
				output.println("Got an error response of " + soapConn.getResponseCode());
				response = new DataInputStream(soapConn.getErrorStream());
			}

			StringBuilder builder = new StringBuilder();
			BufferedReader breader = new BufferedReader(new InputStreamReader(response, "UTF-8"));
			line = "";
			while ((line = breader.readLine()) != null) {
				builder.append(line);
			}
			output.println(builder.toString());

		} catch (Exception e) {
			System.err.println(e);
			e.printStackTrace(System.err);
		} finally {
			try {
				if (request != null) {
					request.close();
					request = null;
				}
				if (soapRequest != null) {
					soapRequest.close();
					soapRequest = null;
				}
				if (response != null) {
					response.close();
					response = null;
				}
				if (soapRequestBuff != null) {
					soapRequestBuff.clear();
				}
				if (attachmentsBuff != null) {
					attachmentsBuff.clear();
				}
			} catch (Exception e) {
				System.err.println(e);
			}
		}
	}


	public void submitRequest(PrintStream output, String cert) {
		DataInputStream response = null;
		DataOutputStream request = null;
		File inputFile = null;
		DataInputStream soapRequest = null;
		String line = null;
		byte[] buffer = new byte[1024];
		int size = 0;
		HttpURLConnection soapConn = null;
		try {
			SSLContext sslCtx = Security.getSSLContext();
			sslCtx.init(null, getTrustAllCerts(), new java.security.SecureRandom());
			HttpsURLConnection.setDefaultSSLSocketFactory(sslCtx.getSocketFactory());

			URL wsdlUrl = new URL(getUrlString());
			URLConnection urlConn = wsdlUrl.openConnection();
			if (!(urlConn instanceof HttpURLConnection)) {
				output.println("Connection not a valid http connection");
				return;
			}
			soapConn = (HttpURLConnection) urlConn;
			soapConn.setRequestMethod("POST");
			soapConn.setRequestProperty(MIME.HeaderContentType, Constants.UTF8ContentTypeDeclaration);
			if (getUsernamePassword() != null) {
				byte[] byteForm = EncodingUtil.getBytes(getUsernamePassword(), "ISO-8859-1");
				StringBuffer value = new StringBuffer("Basic ");
				value.append(EncodingUtil.getAsciiString(byteForm));
				soapConn.setRequestProperty("authorization", value.toString());
			}

			soapConn.setRequestProperty("X-Client-Cert", cert);

			soapConn.setDoOutput(true);
			inputFile = new File(getInput());
			soapRequest = new DataInputStream(new FileInputStream(inputFile));

			request = new DataOutputStream(soapConn.getOutputStream());
			while (true) {
				size = soapRequest.read(buffer);
				if (size < 0) {
					break;
				}
				request.write(buffer, 0, size);
			}

			if (soapConn.getResponseCode() == 200) {
				response = new DataInputStream(soapConn.getInputStream());
			} else {
				output.println("Got an error response of " + soapConn.getResponseCode());
				response = new DataInputStream(soapConn.getErrorStream());
			}
			while (true) {
				line = response.readLine();
				if (line == null) {
					break;
				}
				output.println(line);
			}
		} catch (Exception e) {
			System.err.println(e);
			e.printStackTrace(System.err);
		} finally {
			try {
				if (request != null) {
					request.close();
					request = null;
				}
				if (soapRequest != null) {
					soapRequest.close();
					soapRequest = null;
				}
				if (response != null) {
					response.close();
					response = null;
				}
			} catch (Exception e) {
				System.err.println(e);
			}
		}
	}

	public void submitRequest(PrintStream output) {
		DataInputStream response = null;
		DataOutputStream request = null;
		File inputFile = null;
		DataInputStream soapRequest = null;
		String line = null;
		byte[] buffer = new byte[1024];
		int size = 0;
		HttpURLConnection soapConn = null;

		try {
			SSLContext sslCtx = Security.getSSLContext();
			sslCtx.init(null, getTrustAllCerts(), new java.security.SecureRandom());
			HttpsURLConnection.setDefaultSSLSocketFactory(sslCtx.getSocketFactory());

			URL wsdlUrl = new URL(getUrlString());
			URLConnection urlConn = wsdlUrl.openConnection();
			if (!(urlConn instanceof HttpURLConnection)) {
				output.println("Connection not a valid http connection");
				return;
			}
			soapConn = (HttpURLConnection) urlConn;
			soapConn.setRequestMethod("POST");
			soapConn.setRequestProperty(MIME.HeaderContentType, Constants.UTF8ContentTypeDeclaration);
			if (getUsernamePassword() != null) {
				byte[] byteForm = EncodingUtil.getBytes(getUsernamePassword(), "ISO-8859-1");
				StringBuffer value = new StringBuffer("Basic ");
				value.append(EncodingUtil.getAsciiString(byteForm));
				soapConn.setRequestProperty("authorization", value.toString());
			}

			soapConn.setDoOutput(true);
			inputFile = new File(getInput());
			soapRequest = new DataInputStream(new FileInputStream(inputFile));

			request = new DataOutputStream(soapConn.getOutputStream());
			while (true) {
				size = soapRequest.read(buffer);
				if (size < 0) {
					break;
				}
				request.write(buffer, 0, size);
			}

			if (soapConn.getResponseCode() == 200) {
				response = new DataInputStream(soapConn.getInputStream());
			} else {
				output.println("Got an error response of " + soapConn.getResponseCode());
				response = new DataInputStream(soapConn.getErrorStream());
			}
			while (true) {
				line = response.readLine();
				if (line == null) {
					break;
				}
				output.println(line);
			}
		} catch (Exception e) {
			System.err.println(e);
			e.printStackTrace(System.err);
		} finally {
			try {
				if (request != null) {
					request.close();
					request = null;
				}
				if (soapRequest != null) {
					soapRequest.close();
					soapRequest = null;
				}
				if (response != null) {
					response.close();
					response = null;
				}
			} catch (Exception e) {
				System.err.println(e);
			}
		}
	}

	public String getHost() {
		return m_host;
	}

	public void setHost(String host) {
		m_host = host;
	}

	public String getPort() {
		return m_port;
	}

	public void setPort(String port) {
		m_port = port;
	}

	public String getRealm() {
		return m_realm;
	}

	public void setRealm(String realm) {
		m_realm = realm;
	}

	public String getEvent() {
		return m_event;
	}

	public void setEvent(String event) {
		m_event = event;
	}

	public String getInput() {
		return m_input;
	}

	public void setInput(String input) {
		m_input = input;
	}

	public String getApp() {
		return m_app;
	}

	public void setApp(String app) {
		m_app = app;
	}

	public String getUsernamePassword() {
		return m_userpass;
	}

	public void setUsernamePassword(String userpass) {
		m_userpass = userpass;
	}

	public boolean isSecure() {
		return m_secure;
	}

	public void setSecure(boolean secure) {
		m_secure = secure;
	}

	public static TrustManager[] getTrustAllCerts() {
		return trustAllCerts;
	}

	public static void setTrustAllCerts(TrustManager[] trustAllCerts) {
		SoapRequestGenerator.trustAllCerts = trustAllCerts;
	}

	public void submitRequestWithSAPPassport(PrintStream output, String sSAPPassport) {
		final String SAPPASSPORTKEY = "SAP-PASSPORT";
		DataInputStream response = null;
		DataOutputStream request = null;
		File inputFile = null;
		DataInputStream soapRequest = null;
		String line = null;
		byte[] buffer = new byte[1024];
		int size = 0;
		HttpURLConnection soapConn = null;

		try {
			SSLContext sslCtx = Security.getSSLContext();
			sslCtx.init(null, getTrustAllCerts(), new java.security.SecureRandom());
			HttpsURLConnection.setDefaultSSLSocketFactory(sslCtx.getSocketFactory());

			URL wsdlUrl = new URL(getUrlString());
			URLConnection urlConn = wsdlUrl.openConnection();
			if (!(urlConn instanceof HttpURLConnection)) {
				output.println("Connection not a valid http connection");
				return;
			}
			soapConn = (HttpURLConnection) urlConn;
			soapConn.setRequestMethod("POST");
			soapConn.setRequestProperty(SAPPASSPORTKEY, sSAPPassport);
			soapConn.setRequestProperty(MIME.HeaderContentType, Constants.UTF8ContentTypeDeclaration);
			if (getUsernamePassword() != null) {
				byte[] byteForm = EncodingUtil.getBytes(getUsernamePassword(), "ISO-8859-1");
				StringBuffer value = new StringBuffer("Basic ");
				value.append(EncodingUtil.getAsciiString(byteForm));
				soapConn.setRequestProperty("authorization", value.toString());
			}

			soapConn.setDoOutput(true);

			inputFile = new File(getInput());
			soapRequest = new DataInputStream(new FileInputStream(inputFile));

			request = new DataOutputStream(soapConn.getOutputStream());
			while (true) {
				size = soapRequest.read(buffer);
				if (size < 0) {
					break;
				}
				request.write(buffer, 0, size);
			}

			if (soapConn.getResponseCode() == 200) {
				response = new DataInputStream(soapConn.getInputStream());
			} else {
				output.println("Got an error response of " + soapConn.getResponseCode());
				response = new DataInputStream(soapConn.getErrorStream());
			}
			while (true) {
				line = response.readLine();
				if (line == null) {
					break;
				}
				output.println(line);
			}
		} catch (Exception e) {
			System.err.println(e);
			e.printStackTrace(System.err);
		} finally {
			try {
				if (request != null) {
					request.close();
					request = null;
				}
				if (soapRequest != null) {
					soapRequest.close();
					soapRequest = null;
				}
				if (response != null) {
					response.close();
					response = null;
				}
			} catch (Exception e) {
				System.err.println(e);
			}
		}
	}
}
