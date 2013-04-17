package org.apache.hadoop.hive.jdbchandler;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.jdbchandler.JDBCSerDe.ColumnMapping;
import org.apache.hadoop.hive.ql.io.HiveInputFormat;
import org.apache.hadoop.hive.serde2.ColumnProjectionUtils;
import org.apache.hadoop.hive.serde2.SerDeException;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileSplit;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RecordReader;
import org.apache.hadoop.mapred.Reporter;

public class HiveDBInputFormat extends HiveInputFormat<LongWritable, ResultSetWritable>{

  static final Log LOG = LogFactory.getLog(HiveDBInputFormat.class);

  private String dbProductName = "DEFAULT";
  private String conditions;
  private Connection connection;
  private String tableName;
  private String[] fieldNames;
  private DBConfiguration dbConf;


  private void initialize(JobConf conf) {
    // TODO Auto-generated method stub
    dbConf = new DBConfiguration(conf);
    dbConf.setInputClass(ResultSetWritable.class);
    try {
      connection = getConnection();

      DatabaseMetaData dbMeta = connection.getMetaData();
      dbProductName = dbMeta.getDatabaseProductName().toUpperCase();
    }
    catch (SQLException ex) {
      throw new RuntimeException(ex);
    }

    tableName = dbConf.getInputTableName();
    fieldNames = dbConf.getInputFieldNames();
    if(fieldNames == null){
   // configure read fields from query.
      List<Integer> readColIDs = ColumnProjectionUtils.getReadColumnIDs(conf);
      String jdbcColumnsMappingSpec = conf.get(JDBCSerDe.JDBC_COLUMNS_MAPPING);
      List<ColumnMapping> columnsMapping = null;
      try {
        columnsMapping = JDBCSerDe.parseColumnsMapping(jdbcColumnsMappingSpec);
      } catch (SerDeException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
        throw new RuntimeException(e.getMessage());
      }

      if (columnsMapping.size() < readColIDs.size()) {
        throw new RuntimeException("Cannot read more columns than the given table contains.");
      }

      boolean addAll = (readColIDs.size() == 0);

      if (!addAll) {
        fieldNames = new String[readColIDs.size()];
        for (int i : readColIDs) {
          fieldNames[i] = columnsMapping.get(i).getColumnName();
        }
      }else {
        fieldNames = new String[columnsMapping.size()];
        for (int i=0;i<columnsMapping.size();i++) {
          fieldNames[i] = columnsMapping.get(i).getColumnName();
        }
      }
    }
    conditions = dbConf.getInputConditions();
  }

  @Override
  public InputSplit[] getSplits(JobConf job, int chunks) throws IOException {
    // TODO Auto-generated method stub
    try {
      initialize(job);
      Statement statement = getConnection().createStatement();

      ResultSet results = statement.executeQuery(getCountQuery());
      results.next();

      long count = results.getLong(1);
      long chunkSize = (count / chunks);

      results.close();
      statement.close();

      Path[] tablePaths = FileInputFormat.getInputPaths(job);


      InputSplit[] splits = new InputSplit[chunks];

      // Split the rows into n-number of chunks and adjust the last chunk
      // accordingly
      for (int i = 0; i < chunks; i++) {
        DBInputSplit split;

        if ((i + 1) == chunks) {
          split = new DBInputSplit(i * chunkSize, count, tablePaths[0]);
        } else {
          split = new DBInputSplit(i * chunkSize, (i * chunkSize) + chunkSize, tablePaths[0]);
        }

        splits[i] = split;
      }

      return splits;
    } catch (SQLException e) {
      throw new IOException(e.getMessage());
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public RecordReader getRecordReader(InputSplit split, JobConf conf,
      Reporter reporter) throws IOException {
    // TODO Auto-generated method stub
    initialize(conf);
    Class inputClass =  dbConf.getInputClass();

    try {
      // use database product name to determine appropriate record reader.
      if (dbProductName.startsWith("ORACLE")) {
        // use Oracle-specific db reader.
        return new OracleDBRecordReader((DBInputSplit) split, inputClass,
            conf, getConnection(), getDBConf(), conditions, fieldNames,
            tableName);
      } else if (dbProductName.startsWith("MYSQL")) {
        // use MySQL-specific db reader.
        return new MySQLDBRecordReader((DBInputSplit) split, inputClass,
            conf, getConnection(), getDBConf(), conditions, fieldNames,
            tableName);
      } else {
        // Generic reader.
        return new DBRecordReader((DBInputSplit) split, inputClass,
            conf, getConnection(), getDBConf(), conditions, fieldNames,
            tableName);
      }
    } catch (SQLException ex) {
      throw new IOException(ex.getMessage());
    }

  }

  /** Returns the query for getting the total number of rows,
   * subclasses can override this for custom behaviour.*/
  protected String getCountQuery() {

    if(dbConf.getInputCountQuery() != null) {
      return dbConf.getInputCountQuery();
    }

    StringBuilder query = new StringBuilder();
    query.append("SELECT COUNT(*) FROM " + tableName);

    if (conditions != null && conditions.length() > 0) {
      query.append(" WHERE " + conditions);
    }
    return query.toString();
  }

  public DBConfiguration getDBConf() {
    return dbConf;
  }

  public Connection getConnection() {
    try {
      if (null == this.connection) {
        // The connection was closed; reinstantiate it.
        this.connection = dbConf.getConnection();
        this.connection.setAutoCommit(false);
        this.connection.setTransactionIsolation(
            Connection.TRANSACTION_SERIALIZABLE);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return connection;
  }


  /**
   * A InputSplit that spans a set of rows
   */
  protected static class DBInputSplit extends FileSplit implements InputSplit {

    private static final String[] EMPTY_ARRAY = new String[] {};
    private long end = 0;
    private long start = 0;

    /**
     * Default Constructor
     */
    public DBInputSplit() {
      super((Path) null, 0, 0, EMPTY_ARRAY);
    }

    /**
     * Convenience Constructor
     *
     * @param start
     *          the index of the first row to select
     * @param end
     *          the index of the last row to select
     */
    public DBInputSplit(long start, long end , Path tablePath) {
      super(tablePath, 0, 0, EMPTY_ARRAY);
      this.start = start;
      this.end = end;
    }

    /** {@inheritDoc} */
    @Override
    public String[] getLocations() throws IOException {
      // TODO Add a layer to enable SQL "sharding" and support locality
      return EMPTY_ARRAY;
    }

    /**
     * @return The index of the first row to select
     */
    @Override
    public long getStart() {
      return start;
    }

    /**
     * @return The index of the last row to select
     */
    public long getEnd() {
      return end;
    }

    /**
     * @return The total row count in this split
     */
    @Override
    public long getLength() {
      return end - start;
    }

    /** {@inheritDoc} */
    @Override
    public void readFields(DataInput input) throws IOException {
      super.readFields(input);
      start = input.readLong();
      end = input.readLong();
    }

    /** {@inheritDoc} */
    @Override
    public void write(DataOutput output) throws IOException {
      super.write(output);
      output.writeLong(start);
      output.writeLong(end);
    }
  }

}
