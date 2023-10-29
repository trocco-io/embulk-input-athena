package org.embulk.input.athena;

import java.io.File;
import java.io.FileFilter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import org.embulk.config.ConfigDiff;
import org.embulk.config.ConfigException;
import org.embulk.config.ConfigSource;
import org.embulk.config.TaskReport;
import org.embulk.config.TaskSource;
import org.embulk.input.jdbc.ToStringMap;
import org.embulk.spi.BufferAllocator;
import org.embulk.spi.Column;
import org.embulk.spi.ColumnVisitor;
import org.embulk.spi.Exec;
import org.embulk.spi.InputPlugin;
import org.embulk.spi.PageBuilder;
import org.embulk.spi.PageOutput;
import org.embulk.spi.Schema;
import org.embulk.spi.time.Timestamp;
import org.embulk.util.config.Config;
import org.embulk.util.config.ConfigDefault;
import org.embulk.util.config.ConfigMapper;
import org.embulk.util.config.ConfigMapperFactory;
import org.embulk.util.config.Task;
import org.embulk.util.config.TaskMapper;
import org.embulk.util.config.units.SchemaConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AthenaInputPlugin implements InputPlugin
{
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    private static final ConfigMapperFactory CONFIG_MAPPER_FACTORY = ConfigMapperFactory.builder().addDefaultModules().build();

    public interface PluginTask extends Task
    {
        @Config("driver_path")
        @ConfigDefault("null")
        public Optional<String> getDriverPath();

        // database (required string)
        @Config("database")
        public String getDatabase();

        // athena_url (required string)
        @Config("athena_url")
        public String getAthenaUrl();

        // s3_staging_dir (required string)
        @Config("s3_staging_dir")
        public String getS3StagingDir();

        // access_key (required string)
        @Config("access_key")
        public String getAccessKey();

        // secret_key (required string)
        @Config("secret_key")
        public String getSecretKey();

        // query (required string)
        @Config("query")
        public String getQuery();

        // if you get schema from config
        @Config("columns")
        public SchemaConfig getColumns();

        @Config("options")
        @ConfigDefault("{}")
        public ToStringMap getOptions();

        @Config("null_to_zero")
        @ConfigDefault("false")
        public boolean getNullToZero();

    }

    @Override
    public ConfigDiff transaction(ConfigSource config, InputPlugin.Control control)
    {
        final ConfigMapper configMapper = CONFIG_MAPPER_FACTORY.createConfigMapper();
        final PluginTask task = configMapper.map(config, PluginTask.class);

        Schema schema = task.getColumns().toSchema();
        int taskCount = 1; // number of run() method calls

        return resume(task.toTaskSource(), schema, taskCount, control);
    }

    @Override
    public ConfigDiff resume(TaskSource taskSource, Schema schema, int taskCount, InputPlugin.Control control)
    {
        control.run(taskSource, schema, taskCount);
        return CONFIG_MAPPER_FACTORY.newConfigDiff();
    }

    @Override
    public void cleanup(TaskSource taskSource, Schema schema, int taskCount, List<TaskReport> successTaskReports)
    {
    }

    @Override
    public TaskReport run(TaskSource taskSource, Schema schema, int taskIndex, PageOutput output)
    {
        final TaskMapper taskMapper = CONFIG_MAPPER_FACTORY.createTaskMapper();
        final PluginTask task = taskMapper.map(taskSource, PluginTask.class);
        BufferAllocator allocator = Exec.getBufferAllocator();
        // TODO: use Exec.getPageBuilder(bufferAllocator, schema, output) after embulk v0.10
        PageBuilder pageBuilder = new PageBuilder(allocator, schema, output);

        // Write your code here :)

        Connection connection = null;
        Statement statement = null;
        try {
            connection = getAthenaConnection(task);
            statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(task.getQuery());
            boolean nullToZero = task.getNullToZero();

            while (resultSet.next()) {
                schema.visitColumns(new ColumnVisitor()
                {
                    @Override
                    public void timestampColumn(Column column)
                    {
                        try {
                            java.sql.Timestamp t = resultSet.getTimestamp(column.getName());
                            if (resultSet.wasNull() && !nullToZero){
                                pageBuilder.setNull(column);
                            }
                            else {
                                pageBuilder.setTimestamp(column, Timestamp.ofEpochMilli(t.getTime()));
                            }
                        }
                        catch (SQLException e) {
                            e.printStackTrace();
                            throw new RuntimeException(e);
                        }
                    }

                    @Override
                    public void stringColumn(Column column)
                    {
                        try {
                            pageBuilder.setString(column, resultSet.getString(column.getName()));
                        }
                        catch (SQLException e) {
                            e.printStackTrace();
                            throw new RuntimeException(e);
                        }
                    }

                    @Override
                    public void longColumn(Column column)
                    {
                        try {
                            long ret = resultSet.getLong(column.getName());
                            if (resultSet.wasNull() && !nullToZero){
                                pageBuilder.setNull(column);
                            }
                            else {
                                pageBuilder.setLong(column, ret);
                            }
                        }
                        catch (SQLException e) {
                            e.printStackTrace();
                            throw new RuntimeException(e);
                        }
                    }

                    @Override
                    public void doubleColumn(Column column)
                    {
                        try {
                            double ret = resultSet.getDouble(column.getName());
                            if (resultSet.wasNull() && !nullToZero){
                                pageBuilder.setNull(column);
                            }
                            else {
                                pageBuilder.setDouble(column, ret);
                            }
                        }
                        catch (SQLException e) {
                            e.printStackTrace();
                            throw new RuntimeException(e);
                        }
                    }

                    @Override
                    public void booleanColumn(Column column)
                    {
                        try {
                            boolean ret = resultSet.getBoolean(column.getName());
                            if (resultSet.wasNull() && !nullToZero){
                                pageBuilder.setNull(column);
                            }
                            else {
                                pageBuilder.setBoolean(column, ret);
                            }
                        }
                        catch (SQLException e) {
                            e.printStackTrace();
                            throw new RuntimeException(e);
                        }
                    }

                    @Override
                    public void jsonColumn(Column column)
                    {
                        // TODO:
                    }
                });

                pageBuilder.addRecord();
            }
            pageBuilder.finish();

            pageBuilder.close();
            resultSet.close();
            connection.close();
        }
        catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
        finally {
            try {
                if (statement != null) {
                    statement.close();
                }
            }
            catch (Exception ex) { }
            try {
                if (connection != null) {
                    connection.close();
                }
            }
            catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        return CONFIG_MAPPER_FACTORY.newTaskReport();
    }

    @Override
    public ConfigDiff guess(ConfigSource config)
    {
        return CONFIG_MAPPER_FACTORY.newConfigDiff();
    }

    protected Connection getAthenaConnection(PluginTask task) throws ClassNotFoundException, SQLException
    {
        loadDriver("com.simba.athena.jdbc.Driver", task.getDriverPath());
        Properties properties = new Properties();
        properties.put("s3_staging_dir", task.getS3StagingDir());
        properties.put("user", task.getAccessKey());
        properties.put("password", task.getSecretKey());
        properties.put("schema", task.getDatabase());
        properties.putAll(task.getOptions());

        return DriverManager.getConnection(task.getAthenaUrl(), properties);
    }

    //
    // copy from embulk-input-jdbc
    //

    protected void loadDriver(String className, Optional<String> driverPath)
    {
        if (driverPath.isPresent()) {
            addDriverJarToClasspath(driverPath.get());
        }
        else {
            try {
                // Gradle test task will add JDBC driver to classpath
                Class.forName(className);
            }
            catch (ClassNotFoundException ex) {
                File root = findPluginRoot();
                File driverLib = new File(root, "default_jdbc_driver");
                File[] files = driverLib.listFiles(new FileFilter() {
                    @Override
                    public boolean accept(File file)
                    {
                        return file.isFile() && file.getName().endsWith(".jar");
                    }
                });
                if (files == null || files.length == 0) {
                    throw new RuntimeException("Cannot find JDBC driver in '" + root.getAbsolutePath() + "'.");
                }
                else {
                    for (File file : files) {
                        logger.info("JDBC Driver = " + file.getAbsolutePath());
                        addDriverJarToClasspath(file.getAbsolutePath());
                    }
                }
            }
        }

        // Load JDBC Driver
        try {
            Class.forName(className);
        }
        catch (ClassNotFoundException ex) {
            throw new RuntimeException(ex);
        }
    }

    protected void addDriverJarToClasspath(String glob)
    {
        // TODO match glob
        final ClassLoader loader = getClass().getClassLoader();
        if (!(loader instanceof URLClassLoader)) {
            throw new RuntimeException("Plugin is not loaded by URLClassLoader unexpectedly.");
        }
        if (!"org.embulk.plugin.PluginClassLoader".equals(loader.getClass().getName())) {
            throw new RuntimeException("Plugin is not loaded by PluginClassLoader unexpectedly.");
        }
        Path path = Paths.get(glob);
        if (!path.toFile().exists()) {
            throw new ConfigException("The specified driver jar doesn't exist: " + glob);
        }
        final Method addPathMethod;
        try {
            addPathMethod = loader.getClass().getMethod("addPath", Path.class);
        } catch (final NoSuchMethodException ex) {
            throw new RuntimeException("Plugin is not loaded a ClassLoader which has addPath(Path), unexpectedly.");
        }
        try {
            addPathMethod.invoke(loader, Paths.get(glob));
        } catch (final IllegalAccessException ex) {
            throw new RuntimeException(ex);
        } catch (final InvocationTargetException ex) {
            final Throwable targetException = ex.getTargetException();
            if (targetException instanceof MalformedURLException) {
                throw new IllegalArgumentException(targetException);
            } else if (targetException instanceof RuntimeException) {
                throw (RuntimeException) targetException;
            } else {
                throw new RuntimeException(targetException);
            }
        }
    }

    protected File findPluginRoot()
    {
        try {
            URL url = getClass().getResource("/" + getClass().getName().replace('.', '/') + ".class");
            if (url.toString().startsWith("jar:")) {
                url = new URL(url.toString().replaceAll("^jar:", "").replaceAll("![^!]*$", ""));
            }

            File folder = new File(url.toURI()).getParentFile();
            for (;; folder = folder.getParentFile()) {
                if (folder == null) {
                    throw new RuntimeException("Cannot find 'embulk-input-xxx' folder.");
                }

                if (folder.getName().startsWith("embulk-input-")) {
                    return folder;
                }
            }
        }
        catch (MalformedURLException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
