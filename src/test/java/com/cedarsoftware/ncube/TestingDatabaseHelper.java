package com.cedarsoftware.ncube;

import java.sql.SQLException;

/**
 * Created by kpartlow on 10/28/2014.
 */
public class TestingDatabaseHelper
{
    public static int MYSQL = 1;
    public static int HSQL = 2;
    public static int ORACLE = 3;

    public static int test_db = HSQL;

    private static Object getProxyInstance() throws Exception
    {
        if (test_db == HSQL) {
            return HsqlTestingDatabaseManager.class.newInstance();
        }

        if (test_db == MYSQL) {
            return MySqlTestingDatabaseManager.class.newInstance();
        }

        throw new IllegalArgumentException("Unknown Database:  " + test_db);
    }

    public static NCubePersister getPersister() throws Exception
    {
        return new NCubeJdbcPersisterAdapter(createJdbcConnectionProvider());
    }

    public static JdbcConnectionProvider createJdbcConnectionProvider() throws Exception
    {
        if (test_db == HSQL) {
            return new TestingConnectionProvider(null, "jdbc:hsqldb:mem:testdb", "sa", "");
        }

        if (test_db == MYSQL) {
            return new TestingConnectionProvider(null, "jdbc:mysql://127.0.0.1:3306/ncube?autoCommit=true", "ncube", "ncube");
        }

        if (test_db == ORACLE) {
            return new TestingConnectionProvider("oracle.jdbc.driver.OracleDriver", "jdbc:oracle:thin:@cvgli59.td.afg:1526:uwdeskd", "ra_desktop", "p0rtal");
        }

        throw new IllegalArgumentException("Unknown Database:  " + test_db);
    }

    public static TestingDatabaseManager getTestingDatabaseManager() throws Exception
    {
        if (test_db == HSQL) {
            return new HsqlTestingDatabaseManager(createJdbcConnectionProvider());
        }

        if (test_db == MYSQL) {
            return new MySqlTestingDatabaseManager(createJdbcConnectionProvider());
        }

        //  Don't manager tables for Oracle
        return new TestingDatabaseManager()
        {
            @Override
            public void setUp() throws SQLException
            {
            }

            @Override
            public void tearDown() throws SQLException
            {
            }
        };
    }

    public static void setupDatabase() throws Exception
    {
        getTestingDatabaseManager().setUp();
        NCubeManager.setNCubePersister(TestingDatabaseHelper.getPersister());

        setupTestClassPaths();
    }

    public static void setupTestClassPaths() throws Exception
    {
        NCubeManager.getNCubeFromResource(TestNCubeManager.defaultSnapshotApp, "sys.classpath.tests.json");
        NCubeManager.getNCubeFromResource(ApplicationID.defaultAppId, "sys.classpath.tests.json");

        //  This forces the load of the cube in the cache since many tests do not use getCube();
        //NCubeManager.getCube(TestNCubeManager.defaultSnapshotApp, "sys.classpath");
        //NCubeManager.getCube(ApplicationId.defaultAppId, "sys.classpath");
    }

    // TODO:  I don't think we need this anymore
    //    static
    //    {
    //        ApplicationID appId = new ApplicationID(ApplicationID.DEFAULT_TENANT, ApplicationID.DEFAULT_APP, ApplicationID.DEFAULT_VERSION, ReleaseStatus.SNAPSHOT.name());
    //        urlClassLoaders.put(appId, new CdnClassLoader(NCubeManager.class.getClassLoader(), true, true));
    //    }



    public static void tearDownDatabase() throws Exception
    {
        getTestingDatabaseManager().tearDown();
        NCubeManager.clearCache();
    }
}