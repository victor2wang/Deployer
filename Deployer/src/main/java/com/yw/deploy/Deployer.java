package com.yw.deploy;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Properties;
import java.util.Random;

import javax.sql.DataSource;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import oracle.ucp.jdbc.PoolDataSource;
import oracle.ucp.jdbc.PoolDataSourceFactory;

public class Deployer implements Runnable {

	private static Logger log = Logger.getLogger(Deployer.class.getName());
	private static final long SLEEP = 5000L;// 5s
	private static final long UPDATE_SYSTEM_TIME = 60 * 1000;// 1min
	private static final long BAK_DB_INTERVAL = 500 * 60 * 1000;//5 minutes
	private long lastCheckSystemTime = System.currentTimeMillis();
	private long lastDBBakTime = System.currentTimeMillis();
	private boolean running;
	private static Deployer deployManager = null;

	private static String USER = null;
	private static String PASS = null;
	private static String SID = null;
	private static DataSource ds = null;

	private int bak_minute = 0;
	private int bak_hour = 0;
	private boolean minuteModified = false;
	private static final int TIME_UNIT = 5;//5 minutes based
	private static final int UNIT_NUM = 36;//36*5=3hours
	
	public Deployer() {
		this.running = true;
	}

	public String executeCommand(String command, boolean waitForResponse) {

		String response = "";

		ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
		pb.redirectErrorStream(true);

		log.debug("Linux command: " + command);

		try {
			Process shell = pb.start();

			if (waitForResponse) {

				// To capture output from the shell
				InputStream shellIn = shell.getInputStream();

				// Wait for the shell to finish and get the return code
				int shellExitStatus = shell.waitFor();
				log.debug("Exit status" + shellExitStatus);

				response = convertStreamToStr(shellIn);

				// log.debug("responseddd:" + response);

				shellIn.close();
			}

		}

		catch (IOException e) {
			log.error("Error occured while executing Linux command. Error Description: " + e.getMessage());
		}

		catch (InterruptedException e) {
			log.error("Error occured while executing Linux command. Error Description: " + e.getMessage());
		}

		return response;
	}

	/*
	 * To convert the InputStream to String we use the Reader.read(char[]
	 * buffer) method. We iterate until the Reader return -1 which means there's
	 * no more data to read. We use the StringWriter class to produce the
	 * string.
	 */

	public String convertStreamToStr(InputStream is) throws IOException {

		if (is != null) {
			Writer writer = new StringWriter();

			char[] buffer = new char[1024];
			try {
				Reader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
				int n;
				while ((n = reader.read(buffer)) != -1) {
					writer.write(buffer, 0, n);
				}
			} finally {
				is.close();
			}
			return writer.toString();
		} else {
			return "";
		}
	}

	public static Deployer getInstance() {

		if (deployManager == null) {
			deployManager = new Deployer();
		}

		return deployManager;
	}

	public static void main(String[] args) {

		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
		log.setLevel(Level.DEBUG);
		log.info("Start" + sdf.format(new Date()));

		if (args.length == 3) {
			SID = args[0];
			USER = args[1];
			PASS = args[2];
		} else if (args.length == 1) {
			SID = args[0];
		}

		try {

			Thread.sleep(1000);
			String response = getInstance().executeCommand("./start-pt.sh jboss 0", true);
			log.debug("response:jboss-start:" + response);
			Thread.sleep(1000);

			String response2 = getInstance().executeCommand("./start-pt.sh socket 0", true);
			log.debug("response:sockket-start:" + response2);
			Thread.sleep(1000);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		new Thread(Deployer.getInstance()).start();

		//manually test hdfile
		log.info("=" + getMD5Checksum("60022480d60103280a737310bddd15df" + "fdtbjuhyr767676767676676767688990gfh".substring(8, 32)));
		
	}

	public void process() {

		try {

			int serverType = 0;
			int deployflag = this.getSysvalue(34L);

			int dbflag = 0;
			int t3flag = 0;
			int serverflag = 0;

			if (deployflag == 1) {
				String response1 = getInstance().executeCommand("./get-config.sh", true);
				log.debug("response-config:" + response1);
				Thread.sleep(2000);

				try {
					Properties deployProperties = new Properties();
					FileInputStream file = new FileInputStream("./deploy.properties");
					deployProperties.load(file);
					file.close();
					dbflag = Integer.parseInt(deployProperties.getProperty("db.main"));
					t3flag = Integer.parseInt(deployProperties.getProperty("db.t3"));
					serverflag = Integer.parseInt(deployProperties.getProperty("server"));
					serverType = Integer.parseInt(deployProperties.getProperty("server.type"));
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}

				updateSysvalue(34L, 0+"");
			}

			log.info("dbflag,t3flag,serverflag,serverType={" + dbflag + "," + t3flag + "," + serverflag + "," + serverType + "}");

			if (dbflag == 1 || t3flag == 1 || serverflag == 1) {

				String response1 = getInstance().executeCommand("./stop-pt.sh", true);
				log.debug("response:stop-server:" + response1);
				Thread.sleep(5000);

				if (dbflag == 1) {

					String responseM = null;

					if ("XE".equalsIgnoreCase(SID)) {
						responseM = getInstance().executeCommand("./runDb.sh main " + serverType, true);
					} else {
						responseM = getInstance().executeCommand("./runDbOne.sh main " + serverType, true);
					}

					log.debug("response:db-main:" + responseM);
					Thread.sleep(5000);
					
					// HASH
					File hdFile = new File("./hdfile.properties");
					if (hdFile.exists()) {

						try {

							String responseSerial = getInstance().executeCommand(
									"/sbin/udevadm info --query=property --name=sda|grep ID_SERIAL_SHORT", true);
							log.debug("response-hdserial:" + responseSerial.split("=")[1]);

							Properties hdProperties = new Properties();
							FileInputStream file = new FileInputStream("./hdfile.properties");
							hdProperties.load(file);
							file.close();
							log.debug("response-hdserial1:" + hdProperties.getProperty("str"));
							updateSysvalue(35L,
									getMD5Checksum(responseSerial.split("=")[1] + hdProperties.getProperty("str").substring(8, 32)));

						} catch (Exception e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}

						hdFile.delete();
						Thread.sleep(1000);
						hdFile.createNewFile();
						Thread.sleep(500);
						hdFile.delete();
					}
					// end					
					
					Thread.sleep(10000);
					
					if (serverType != 0) {
						updateSysvalue(100L, serverType +"");	
					}
				}

				if (t3flag == 1) {

					String responseT3 = null;
					if ("XE".equalsIgnoreCase(SID)) {
						responseT3 = getInstance().executeCommand("./runDb.sh T3 " + serverType, true);
					} else {
						responseT3 = getInstance().executeCommand("./runDbOne.sh T3 " + serverType, true);
					}
					log.debug("response:db-T3:" + responseT3);
					Thread.sleep(10000);
				}

				if (serverflag == 1) {
					String response = getInstance().executeCommand("./start-tt.sh jboss 1", true);
					log.debug("response:" + response);
					Thread.sleep(5000);

					String response2 = getInstance().executeCommand("./start-tt.sh socket 1", true);
					log.debug("response2:" + response2);
					Thread.sleep(1000);

				} else {
					String response = getInstance().executeCommand("./start-tt.sh jboss 0", true);
					log.debug("response:" + response);
					Thread.sleep(5000);

					String response2 = getInstance().executeCommand("./start-tt.sh socket 0", true);
					log.debug("response2:" + response2);
					Thread.sleep(1000);
				}
			}

		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	
	public void checkBackup(String timestr,String removeLastMon) {

		try {
			
			String dumpName = this.getSysvalueStr(5L) + "_" + (this.getSysvalue(100L)==1?"TT":"WW") + "_" + timestr + "_FULL";
			String removeDumpName = this.getSysvalueStr(5L) + "_" + (this.getSysvalue(100L)==1?"TT":"WW") + "_" + removeLastMon;
			String response1 = getInstance().executeCommand("./dbaBak.sh " + dumpName + " " + removeDumpName, true);
			log.debug("response-dbaBak:" + response1);
			Thread.sleep(5000);			

			//upload flag update
			updateSysvalue(111L, "1");
			
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	public void run() {
		// TODO Auto-generated method stub
		while (running) {
			try {

				try {
					Thread.sleep(SLEEP);
				} catch (InterruptedException ex) {
					log.error("InterruptedException : " + ex.getMessage());
				}

				if (System.currentTimeMillis() - lastCheckSystemTime > UPDATE_SYSTEM_TIME) {

					process();

					lastCheckSystemTime = System.currentTimeMillis();
				}

				
				if (System.currentTimeMillis() - lastDBBakTime > BAK_DB_INTERVAL) {

					Calendar today = Calendar.getInstance();
					int hours = today.get(Calendar.HOUR_OF_DAY);
					int minutes = today.get(Calendar.MINUTE);
					
					String[] bak_sche_str = this.getSysvalueStr(39L).trim().split(",");
					boolean timeToBak = false;
					
					//choose minute randomly
					if (!minuteModified) {
						int rNum = new Random().nextInt(UNIT_NUM); //5 minutes based
						bak_hour = rNum*TIME_UNIT/60;
						bak_minute = rNum*TIME_UNIT%60;
						
						log.info("current hours-0:" + hours);
						log.info("current minutes-0:" + minutes);
						log.info("current bak_hour-0:" + bak_hour);
						log.info("current bak_minute-0:" + bak_minute);
						
						minuteModified = true;
					}
					
					for (int i = 0; i < bak_sche_str.length; i++) {
						if (hours == Integer.parseInt(bak_sche_str[i]) + bak_hour 
							&& (minutes > bak_minute && minutes <= bak_minute + TIME_UNIT) ) {
							timeToBak = true;
							break;
						}
					}
					
					
					if (timeToBak) {
						
						log.info("current hours:" + hours);
						log.info("current bak_hour:" + bak_hour);
						log.info("current minutes:" + minutes);
						log.info("current bak_minute:" + bak_minute);
						
						SimpleDateFormat sdf = new SimpleDateFormat("yyyyww-MMdd-HH");
						String dateStr = sdf.format(today.getTime());
						today.add(Calendar.WEEK_OF_YEAR, -1);
						SimpleDateFormat sdf1 = new SimpleDateFormat("yyyyww");
						String removeLastMon = sdf1.format(today.getTime());
						checkBackup(dateStr,removeLastMon);
					}

					lastDBBakTime = System.currentTimeMillis();
				}
				
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();

				log.error("DeployManager: " + e.getMessage());
			}

		}
	}

	public DataSource getDataSource() throws SQLException {

		// if (dbConnection == null) {

		if (USER == null) {
			USER = "user";
		}

		if (PASS == null) {
			PASS = "1234";
		}

		if (SID == null) {
			SID = "XE";
		}

		//log.info("USER,PASS,SID={" + USER + "," + PASS + "," + SID + "}");
		log.info("USER,SID={" + USER + "," + SID + "}");
		
		final String DB_DRIVER = "oracle.jdbc.driver.OracleDriver";
		final String DB_CONNECTION = "jdbc:oracle:thin:@localhost:1521:" + SID;
		final String DB_USER = USER;
		final String DB_PASSWORD = PASS;
		// Class.forName(DB_DRIVER);
		// return DriverManager.getConnection(DB_CONNECTION, DB_USER,
		// DB_PASSWORD);

		PoolDataSource pds = PoolDataSourceFactory.getPoolDataSource();

		pds.setConnectionFactoryClassName("oracle.jdbc.pool.OracleDataSource");
		// pds.setURL("jdbc:oracle:thin:@//localhost:1521/XE");
		pds.setURL(DB_CONNECTION);
		pds.setUser(DB_USER);
		pds.setPassword(DB_PASSWORD);
		pds.setInitialPoolSize(2);

		return pds;
		// }
	}

	public Connection getConnection() throws SQLException {
		if (ds == null) {
			ds = getDataSource();
		}

		return ds.getConnection();
	}

	public int getSysvalue(Long key) {

		Connection conn = null;
		PreparedStatement pstmt = null;
		String sqlString = "select value from TEST_VALUE where ID = ?";
		int value = -1;
		try {
			conn = getConnection();
			pstmt = conn.prepareStatement(sqlString);
			pstmt.setLong(1, key);
			ResultSet rs = pstmt.executeQuery();

			if (rs != null && rs.next()) {
				value = rs.getInt(1);
			}
		} catch (SQLException ex) {

			log.debug("ex.getMessage():" + ex.getMessage());

			if (ex.getMessage().contains("01017")) {
				value = 1;
			}
		} finally {
			try {
				if (pstmt != null)
					pstmt.close();
				if (conn != null)
					conn.close();

			} catch (SQLException ex) {
				ex.printStackTrace();
			}

		}

		return value;
	}

	
	public String getSysvalueStr(Long key) {

		Connection conn = null;
		PreparedStatement pstmt = null;
		String sqlString = "select value from TEST_VALUE where ID = ?";
		String value = "";
		try {
			conn = getConnection();
			pstmt = conn.prepareStatement(sqlString);
			pstmt.setLong(1, key);
			ResultSet rs = pstmt.executeQuery();

			if (rs != null && rs.next()) {
				value = rs.getString(1);
			}
		} catch (SQLException ex) {
			log.debug("getSysvalueStr:" + ex);
		} finally {
			try {
				if (pstmt != null)
					pstmt.close();
				if (conn != null)
					conn.close();

			} catch (SQLException ex) {
				ex.printStackTrace();
			}

		}

		return value;
	}
	
	public void updateSysvalue(Long key, String value) {

		Connection conn = null;
		PreparedStatement pstmt = null;
		String sqlString = "update TEST_VALUE set VALUE = ?  where ID = ?";
		try {
			conn = getConnection();
			pstmt = conn.prepareStatement(sqlString);
			pstmt.setString(1, value);
			pstmt.setLong(2, key);
			pstmt.execute();

			conn.commit();
		} catch (SQLException ex) {
			ex.printStackTrace();
		} finally {
			try {
				if (pstmt != null)
					pstmt.close();
				if (conn != null)
					conn.close();

			} catch (SQLException ex) {
				ex.printStackTrace();
			}

		}

	}
	
	
	public static String getMD5Checksum(String userName) {

		try {
			java.security.MessageDigest md = java.security.MessageDigest
					.getInstance("MD5");
			byte[] array = md.digest(userName.getBytes());
			StringBuffer sb = new StringBuffer();
			for (int i = 0; i < array.length; ++i) {
				sb.append(Integer.toHexString((array[i] & 0xFF) | 0x100)
						.substring(1, 3));
			}

			String hashtext = sb.toString();

			while (hashtext.length() < 32) {
				hashtext = "0" + hashtext;
			}

			return hashtext;
		} catch (java.security.NoSuchAlgorithmException e) {
			e.fillInStackTrace();
		}
		return null;
	}
}
