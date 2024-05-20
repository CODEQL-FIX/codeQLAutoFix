/*
    Copyright (c) 1996-2016 Ariba, Inc.
    All rights reserved. Patents pending.

    $Id$

    Responsible: rwells
*/

package ariba.tool.ezconfig;

import java.sql.*;

/**
    Little Java application that handles the CreateDBAccount action for ez (EZConfig).

    Currently this utility only supports Oracle & Sybase

    @aribaapi ariba
*/
public class EzCreateDBAccount
{
    private static void usageError ()
    {
        error("Usage: EzCreateDBAccount <jdbcURL> <newUserName> <argName> <dbType> <dbServer> [<userPassword>]");
    }

    public static void main (String[] args)
    {
        if (args.length < 5 || args.length > 6) {
            usageError();
        }

        String jdbcUrl = args[0];
        String newUser = args[1];
        String newUserKey = args[2];
        String dbType  = args[3];
        String dbServer  = args[4];

        /**
         * Optional parameters: 'userPassword', if not defined then default is same as schema user name
         * for now used only for HanaDBAccount
         */
        String userPassword = newUser;
        if (args.length > 5 && !args[5].isEmpty()) {
            userPassword = args[5];
            info("schema password is set to \"" + userPassword + "\" ");
        } else
            info("schema password is not defined, defaulting to \"" + userPassword + "\" same as username");

        DBAccountUtil accountUtil = getDBAccountUtil(jdbcUrl, dbType, dbServer, userPassword);
        accountUtil.create(newUser, newUserKey);

    }

    /**
     *
     * @param jdbcUrl
     * @param dbType
     * @param dbServer
     * @param userPassword
     * @return
     */
    private static DBAccountUtil getDBAccountUtil(String jdbcUrl, String dbType, String dbServer, String userPassword) {
        DBAccountUtil dbAccountUtil = null;
        if (dbType.equalsIgnoreCase("oracle")) {
            dbAccountUtil = new OracleDBAccount(jdbcUrl, dbServer);
        } else if (dbType.equalsIgnoreCase("sybase")) {
            dbAccountUtil = new ASEDBAccount(jdbcUrl, dbServer);
        } else if (dbType.equalsIgnoreCase("hana")) {
            dbAccountUtil = new HanaDBAccount(jdbcUrl, dbServer, userPassword);
        } else {
            error("Unsupported dbType " + dbType);
        }
        return dbAccountUtil;
    }

    private static interface DBAccountUtil {
        /**
         * Create a new schema user
         * @param newUser
         * @param newUserkey
         */
        public void create(String newUser, String newUserkey);
    }

    /**
     * Responsible for Oracle db account creation
     */
    private static class OracleDBAccount implements DBAccountUtil {
        private static final String OracleDriverClassPath = "oracle.jdbc.driver.OracleDriver";
        private static final String OracleAdmUser = "oracle";
        private static final String OracleAdmPassword = "oracle";

        private String jdbcUrl;
        private String dbServer;

        public OracleDBAccount(String jdbcUrl, String dbServer) {
            this.jdbcUrl = jdbcUrl;
            this.dbServer = dbServer;
        }

        @Override
        public void create(String newUser, String newUserkey) {
            Connection conn = null;
            try {
                // Under install, in internal/classes/oraclejdbc10g_14.jar
                Class.forName(OracleDriverClassPath); // OK

                conn = DriverManager.getConnection(jdbcUrl, OracleAdmUser, OracleAdmPassword);
                Statement stmt = conn.createStatement();

                String sql = "SELECT COUNT(*) COUNT FROM ALL_USERS WHERE USERNAME = " + dq(newUser);

                ResultSet rs = stmt.executeQuery(sql);
                int count = 0;
                while (rs.next()) {
                    count = rs.getInt(1);
                }
                if (count > 0) {
                    info(newUser + " already exists, will reuse it ...");
                }
                else {
                    info(newUser + " can be created");
                    sql = "CALL NEW_ACCOUNT (" + dq(newUser) + ")";
                    stmt.executeUpdate(sql);
                    info("Account " + newUser + " created and set to " + newUserkey);
                }
            }
            catch (SQLException e) {
                error("Database problem on \"" + jdbcUrl + "\" as " +
                        OracleAdmUser + "/" + OracleAdmPassword + ", got: " + e);
            }
            catch (ClassNotFoundException e) {
                error("Failed to load JDBC driver " + OracleDriverClassPath + ", got: " + e);
            } finally {
                try {
                    conn.close();
                } catch (SQLException e) {
                    error(e.getMessage());
                }
            }
        }
    }

    /**
     * Responsible for ASE db account creation
     */
    private static class ASEDBAccount implements DBAccountUtil {
        private static final String SybaseDriverClassPath = "com.sybase.jdbc4.jdbc.SybDriver";
        private static final String SybaseAdmUser = "sa";
        private static final String SybaseAdmPassword = "sybase123";

        private String jdbcUrl;
        private String dbServer;

        public ASEDBAccount(String jdbcUrl, String dbServer) {
            this.jdbcUrl = jdbcUrl;
            this.dbServer = dbServer;
        }

        @Override
        public void create(String newUser, String newUserkey) {
            Connection conn = null;
            try {
                // Under install, in internal/classes/oraclejdbc10g_14.jar
                Class.forName(SybaseDriverClassPath); // OK

                conn = DriverManager.getConnection(jdbcUrl, SybaseAdmUser, SybaseAdmPassword);
                Statement stmt = conn.createStatement();

                String sql = "select count(*) from master.dbo.syslogins where name = " + dq(newUser);

                ResultSet rs = stmt.executeQuery(sql);
                int count = 0;
                while (rs.next()) {
                    count = rs.getInt(1);
                }
                if (count > 0) {
                    info(newUser + " already exists, will reuse it ...");
                }
                else {
                    info(newUser + " can be created");
                    String new_account_sp = "exec new_account @uname=?, @tspace=?";
                    PreparedStatement pstmt = conn.prepareStatement(new_account_sp);
                    pstmt.setString(1,newUser);
                    pstmt.setString(2, dbServer);
                    pstmt.execute();

                    info("Account " + newUser + " created and set to " + newUserkey);
                }
            }
            catch (SQLException e) {
                error("Database problem on \"" + jdbcUrl + "\" as " +
                        SybaseAdmUser + "/" + SybaseAdmPassword + ", got: " + e);
            }
            catch (ClassNotFoundException e) {
                error("Failed to load JDBC driver " + SybaseDriverClassPath + ", got: " + e);
            } finally {
                try {
                    conn.close();
                } catch (SQLException e) {
                    error(e.getMessage());
                }
            }
        }
    }

    /**
     * Responsible for HANA db account creation
     */
    private static class HanaDBAccount implements DBAccountUtil {
        private static final String HANADRIVERCLASSPATH = "com.sap.db.jdbc.Driver";
        private static final String HANAMASTERUSER = "HANAUSER";
        private static final String HANAMASTERPASSWORD = "Hanauser1";
        private String jdbcUrl;
        private String dbServer;
        private String userPassword;

        public HanaDBAccount(String jdbcUrl, String dbServer, String userPassword) {
            this.jdbcUrl = jdbcUrl;
            this.dbServer = dbServer;
            this.userPassword = userPassword;
        }

        @Override
        public void create(String newUser, String newUserKey) {
            Connection conn = null;
            Statement stmt = null;
            ResultSet rs = null;
            PreparedStatement pstmt = null;

            try {
                Class.forName(HANADRIVERCLASSPATH);
                conn = DriverManager.getConnection(jdbcUrl, HANAMASTERUSER, HANAMASTERPASSWORD);
                stmt = conn.createStatement();
                String sql = "SELECT COUNT(*) FROM SYS.USERS WHERE USER_NAME=?" ;
                pstmt = conn.prepareStatement(sql);
                pstmt.setString(1, newUser == null ? "null" : newUser);
                rs = pstmt.executeQuery();

                int count = 0;
                while (rs.next()) {
                    count = rs.getInt(1);
                }

                if (count > 0) {
                    info(newUser + " already exists, will reuse it ...");
                } else {
                    info(newUser + " can be created with password " + userPassword);
                    sql = "CREATE USER " + newUser + " PASSWORD " + userPassword;
                    stmt.executeUpdate(sql);
                    sql = "GRANT EXECUTE ON REPOSITORY_REST TO " + newUser;
                    stmt.executeUpdate(sql);
                    sql = "GRANT CATALOG READ TO " + newUser;
                    stmt.executeUpdate(sql);
                    sql = "GRANT EXPORT TO " + newUser;
                    stmt.executeUpdate(sql);
                    sql = "GRANT IMPORT TO " + newUser;
                    stmt.executeUpdate(sql);
                    sql = "ALTER USER " + newUser + " DISABLE PASSWORD LIFETIME";
                    stmt.executeUpdate(sql);
                    info("Account " + newUser + " created and set to " + newUserKey);
                }
            } catch (SQLException e) {
                error("Database problem on \"" + jdbcUrl + "\" as " + HANAMASTERUSER + "/" + HANAMASTERPASSWORD + ", got: " + e);
            } catch (ClassNotFoundException e) {
                error("Failed to load JDBC driver "+ HANADRIVERCLASSPATH +", got: " + e);
            } finally {
                try {
                    if (rs != null)
                        rs.close();
                    if (pstmt != null)
                        pstmt.close();
                    if (stmt != null)
                        stmt.close();
                    if (conn != null)
                        conn.close();
                } catch (SQLException e) {
                    error(e.getMessage());
                }
            }
        }
    }

    /**
        Returns "database quoted" String with its parameter delimited by single quotes and
        with any embedded single quotes doubled, to make the string into a valid SQL
        string literal; except that if the parameter is null or is the string "null", we
        return "null", a valid SQL NULL literal.
    */
    private static String dq (String s)
    {
        if (s == null || s.equals("null")) {
            return "null";
        }
        return ("'" + s.replaceAll("'", "''") + "'");
    }

    private static void info (String msg)
    {
        System.out.println("info   : " + msg); // OK
    }

    private static void error (String msg)
    {
        System.err.println("error  : " + msg); // OK
        System.exit(1); // OK
    }

}
