package com.splicemachine.tutorial.tensorflow;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.sql.Activation;
import com.splicemachine.db.iapi.sql.ResultColumnDescriptor;
import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.db.iapi.types.DataTypeDescriptor;
import com.splicemachine.db.iapi.types.SQLVarchar;
import com.splicemachine.db.impl.jdbc.EmbedConnection;
import com.splicemachine.db.impl.jdbc.EmbedResultSet40;
import com.splicemachine.db.impl.sql.GenericColumnDescriptor;
import com.splicemachine.db.impl.sql.execute.IteratorNoPutResultSet;
import com.splicemachine.db.impl.sql.execute.ValueRow;


public class CreateInputDictionary {
    
    private static int fileSuffix = 0;
    
    private static final Logger LOG = Logger
            .getLogger(CreateInputDictionary.class);
    
    /**
     * Resultset that will be generated by the createInputDictionary
     */
    private static final ResultColumnDescriptor[] INPUT_DICTIONARY_COLUMNS = new GenericColumnDescriptor[]{
            new GenericColumnDescriptor("INPUT_DICT", DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR))
    };
    
    
    /**
     * 
     * @param dictionaryName - Name of the group of records in the INPUT_DICTIONARY
     * @param trainTable - Name of the table with the training data
     * @param testTable - Name of the table with the test Data
     * @param path - Root path for the export process
     * @param returnResultset
     * @throws SQLException
     * @throws StandardException 
     */
    public static void createInputDictionary(String dictionaryName, String trainTable, String testTable, String path, ResultSet[] returnResultset) throws SQLException, StandardException{
        
        
        Connection conn = DriverManager.getConnection("jdbc:splice://localhost:1527/splicedb;user=splice;password=admin");
        
        //Get Columns
        PreparedStatement pstmt = conn.prepareStatement("select COLUMN_NAME from INPUT_DICTIONARY where DICTIONARY_NAME = ? and TYPE = ? order by SEQUENCE");
        pstmt.setString(1, dictionaryName);
        pstmt.setString(2, "COLUMN");
        ResultSet rs = pstmt.executeQuery();
        
        
        //Create the JSON Object to store the definition
        JsonObject inputDict = new JsonObject();
        
        //Contains a list of columns to export 
        StringBuilder exportColumns = new StringBuilder();
        
        JsonArray jsonArr = new JsonArray();
        int numColumns = 0;
        while(rs.next()) {            
            String name = rs.getString(1);
            
            if(numColumns > 0) { exportColumns.append(",");}
            exportColumns.append(name);
            
            jsonArr.add(name);
            numColumns++;
        }
        if(numColumns > 0) {
            inputDict.add("columns", jsonArr);
        }
        
        //Get the categorical columns
        pstmt.clearParameters();
        pstmt.setString(1, dictionaryName);
        pstmt.setString(2, "CATEGORICAL");
        rs = pstmt.executeQuery();        
        jsonArr = new JsonArray();
        numColumns = 0;
        while(rs.next()) {            
            jsonArr.add(rs.getString(1));
            numColumns++;
        }
        if(numColumns > 0) {
            inputDict.add("categorical_columns", jsonArr);
        }
        
        //Get the continuous columns
        pstmt.clearParameters();
        pstmt.setString(1, dictionaryName);
        pstmt.setString(2, "CONTINUOUS");
        rs = pstmt.executeQuery();        
        jsonArr = new JsonArray();
        numColumns = 0;
        while(rs.next()) {            
            jsonArr.add(rs.getString(1));
            numColumns++;
        }
        if(numColumns > 0) {
            inputDict.add("continuous_columns", jsonArr);
        }

        //Get label_column
        pstmt.clearParameters();
        pstmt.setString(1, dictionaryName);
        pstmt.setString(2, "LABEL");
        rs = pstmt.executeQuery();
        if(rs.next()) {
            inputDict.addProperty("label_column", rs.getString(1));
        }
        
        //Get bucketized_columns
        JsonObject buckets = new JsonObject();
        pstmt = conn.prepareStatement("select COLUMN_NAME, LABEL, GROUPING_DETAILS, GROUPING_DETAILS_TYPE from INPUT_DICTIONARY where DICTIONARY_NAME = ? and TYPE = ? order by SEQUENCE");
        pstmt.setString(1, dictionaryName);
        pstmt.setString(2, "BUCKET");
        rs = pstmt.executeQuery(); 
        numColumns = 0;
        if(rs.next()) {
            String column = rs.getString(1);
            String label = rs.getString(2);
            String groupingDetails = rs.getString(3);
            String groupingDetailsType = rs.getString(4);
            
            //Add the column label
            JsonArray bucketValues = new JsonArray();
            bucketValues.add(column);
            
            JsonArray bucketDetailsValues = new JsonArray();
            String[] groupVals = groupingDetails.split(",");
            for(String val: groupVals) {               
                if(groupingDetailsType.equals("INTEGER")) {
                    bucketDetailsValues.add(Integer.parseInt(val.trim()));
                } else {
                    bucketDetailsValues.add(val);
                }
            }    
            //Add the bucket groups
            bucketValues.addAll(bucketDetailsValues);
            buckets.add(label, bucketValues);
            
            numColumns++;
        }
        if(numColumns > 0) {
            inputDict.add("bucketized_columns", buckets);
        }
        
        //Get the train_data_path
        String uniqueFolder = "" + java.util.UUID.randomUUID();
        String exportPathTrain = path + "/train/" + uniqueFolder;
        inputDict.addProperty("train_data_path", exportPathTrain + "/part-r-00000.csv");
        
        //Export the data for the trainTable
        String exportCmd = "EXPORT('" + exportPathTrain + "', false, null, null, null, null) SELECT " + exportColumns.toString() + " FROM " + trainTable;
        exportData(conn, exportCmd, exportPathTrain + "/* ", exportPathTrain + "/traindata.txt");        
        
        //Get test_data_path
        String exportPathTest = path + "/test/" + uniqueFolder;
        inputDict.addProperty("test_data_path", exportPathTest + "/part-r-00000.csv");

        exportCmd = "EXPORT('" + exportPathTest + "', false, null, null, null, null) SELECT " + exportColumns.toString() + " FROM " + testTable;
        exportData(conn, exportCmd, exportPathTest + "/* ", exportPathTest + "/testdata.txt");

        //Build JSON
        Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().setFieldNamingPolicy(FieldNamingPolicy.UPPER_CAMEL_CASE).create();
        String jsonData = gson.toJson(inputDict);
        System.out.println(gson.toJson(jsonData));
        
        List<ExecRow> rows = new ArrayList<ExecRow>();
        ExecRow row = new ValueRow(1);
        row.setColumn(1, new SQLVarchar(jsonData));
        rows.add(row);
        
        Connection conn1 = DriverManager.getConnection("jdbc:default:connection");
        IteratorNoPutResultSet resultsToWrap = wrapResults((EmbedConnection) conn1, rows);
        returnResultset[0] = new EmbedResultSet40((EmbedConnection) conn1, resultsToWrap, false, null, true);
    }
    
    /**
     * Export the data from Splice Machine to the file system
     * 
     * @param conn - Current Connection
     * @param exportCmd - EXPORT command with columns and export location
     * @param exportPath - Used to merge files - it is the directory containing all the files
     * @param exportFileFinal - Used to merge files - indicates what the file final name should be
     * @throws SQLException
     */
    private static void exportData(Connection conn, String exportCmd, String exportPath, String exportFileFinal) throws SQLException {
        LOG.error("Export Command: " + exportCmd);
        Statement stmt = conn.createStatement();
        stmt.executeQuery(exportCmd);
    }
    
    /**
     * This stored procedure needs to return a resultset that is dynamically created.
     * 
     * @param conn - Current Connection
     * @param rows - Instance of output row structure
     * @return
     * @throws StandardException
     */
    private static IteratorNoPutResultSet wrapResults(EmbedConnection conn, Iterable<ExecRow> rows) throws StandardException {
        Activation lastActivation = conn.getLanguageConnection().getLastActivation();
        IteratorNoPutResultSet resultsToWrap = new IteratorNoPutResultSet(rows, INPUT_DICTIONARY_COLUMNS, lastActivation);
        resultsToWrap.openCore();
        return resultsToWrap;
    }

    
    /**
     * Stored procedure used to call a python script.
     * 
     * @param scriptname - Full path to the python script
     */
    public static void callPythonScript(String scriptname) { 
        try{            
            ProcessBuilder pb = new ProcessBuilder("python",scriptname);
            Process p = pb.start();
             
            BufferedReader bfr = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = "";
            LOG.error("Running Python starts: " + line);
            int exitCode = p.waitFor();
            LOG.error("Exit Code : "+exitCode);
            line = bfr.readLine();
            LOG.error("First Line: " + line);
            while ((line = bfr.readLine()) != null){
                LOG.error("Python Output: " + line);
            }

        }catch(Exception e){
                LOG.error("Exception calling pythong script.", e);
        }
    }
    

    
    /**
     * Stored procedure used to call a python script.
     * 
     * @param scriptname - Full path to the python script
     */
    public static void generateModel(String fullModelPath, String type, String modelName, String trainTable, String testTable) { 
        try{
            
            Connection conn = DriverManager.getConnection("jdbc:splice://localhost:1527/splicedb;user=splice;password=admin");
            
            File pythonFile = new File(fullModelPath);
            String parentDir = pythonFile.getParent();
            
            String modelOutputDir = parentDir + "/output";
            String dataDir = parentDir + "/data";
            
            String trainingDataFile = dataDir + "/train/part-r-00000.csv";
            String testDataFile = dataDir + "/test/part-r-00000.csv";
            
            //Get Columns
            PreparedStatement pstmt = conn.prepareStatement("select COLUMN_NAME from INPUT_DICTIONARY where MODEL = ? and TYPE = ? order by SEQUENCE");
            pstmt.setString(1, modelName);
            pstmt.setString(2, "COLUMN");
            ResultSet rs = pstmt.executeQuery();
            
            
            //Create the JSON Object to store the definition
            JsonObject inputDict = new JsonObject();
            
            //Contains a list of columns to export 
            StringBuilder exportColumns = new StringBuilder();
            
            JsonArray jsonArr = new JsonArray();
            int numColumns = 0;
            while(rs.next()) {            
                String name = rs.getString(1);
                
                if(numColumns > 0) { exportColumns.append(",");}
                exportColumns.append(name);
                
                jsonArr.add(name.toLowerCase());
                numColumns++;
            }
            if(numColumns > 0) {
                inputDict.add("columns", jsonArr);
            }
            
            //Get the categorical columns
            pstmt.clearParameters();
            pstmt.setString(1, modelName);
            pstmt.setString(2, "CATEGORICAL");
            rs = pstmt.executeQuery();        
            jsonArr = new JsonArray();
            numColumns = 0;
            while(rs.next()) {            
                jsonArr.add(rs.getString(1).toLowerCase());
                numColumns++;
            }
            if(numColumns > 0) {
                inputDict.add("categorical_columns", jsonArr);
            }
            
            //Get the continuous columns
            pstmt.clearParameters();
            pstmt.setString(1, modelName);
            pstmt.setString(2, "CONTINUOUS");
            rs = pstmt.executeQuery();        
            jsonArr = new JsonArray();
            numColumns = 0;
            while(rs.next()) {            
                jsonArr.add(rs.getString(1).toLowerCase());
                numColumns++;
            }
            if(numColumns > 0) {
                inputDict.add("continuous_columns", jsonArr);
            }

            //Get label_column
            pstmt.clearParameters();
            pstmt.setString(1, modelName);
            pstmt.setString(2, "LABEL");
            rs = pstmt.executeQuery();
            if(rs.next()) {
                inputDict.addProperty("label_column", rs.getString(1).toLowerCase());
            }
            
            //Get bucketized_columns
            JsonObject buckets = new JsonObject();
            pstmt = conn.prepareStatement("select COLUMN_NAME, LABEL, GROUPING_DETAILS, GROUPING_DETAILS_TYPE from INPUT_DICTIONARY where MODEL = ? and TYPE = ? order by SEQUENCE");
            pstmt.setString(1, modelName);
            pstmt.setString(2, "BUCKET");
            rs = pstmt.executeQuery(); 
            numColumns = 0;
            if(rs.next()) {
                String column = rs.getString(1).toLowerCase();
                String label = rs.getString(2).toLowerCase();
                String groupingDetails = rs.getString(3);
                String groupingDetailsType = rs.getString(4);
                
                JsonObject bucketObject = new JsonObject();
                
                //Add the column label
                JsonArray bucketValues = new JsonArray();
                
                JsonArray bucketDetailsValues = new JsonArray();
                String[] groupVals = groupingDetails.split(",");
                for(String val: groupVals) {               
                    if(groupingDetailsType.equals("INTEGER")) {
                        bucketDetailsValues.add(Integer.parseInt(val.trim()));
                    } else {
                        bucketDetailsValues.add(val);
                    }
                }    
                //Add the bucket groups
                bucketValues.addAll(bucketDetailsValues);
                bucketObject.add(column, bucketValues);
                
                buckets.add(label, bucketObject);
                
                numColumns++;
            }
            if(numColumns > 0) {
                inputDict.add("bucketized_columns", buckets);
            }
            
            //Get the crossed
            pstmt = conn.prepareStatement("select GROUPING, COLUMN_NAME from INPUT_DICTIONARY where MODEL = ? and TYPE = ? order by GROUPING,SEQUENCE");
            pstmt.setString(1, modelName);
            pstmt.setString(2, "CROSSED");
            rs = pstmt.executeQuery();
            
            JsonArray crossed_columns = new JsonArray();
            numColumns = 0;
            int currentGrouping = 0;
            JsonArray group = null;
            while(rs.next()) {
                int grouping = rs.getInt(1);
                if(numColumns == 0 || currentGrouping != grouping) {
                    if (numColumns != 0 && currentGrouping != grouping) {
                        crossed_columns.add(group);
                    }
                    group = new JsonArray();
                    group.add(rs.getString(2).toLowerCase());
                    currentGrouping = grouping;
                } else {
                    group.add(rs.getString(2).toLowerCase());
                }               
                numColumns++;
            }
            if(numColumns > 0) {
                crossed_columns.add(group);
                inputDict.add("crossed_columns", crossed_columns);
            }            
            
            
            //Get the train_data_path
            String exportPathTrain = dataDir + "/train";
            inputDict.addProperty("train_data_path", exportPathTrain + "/part-r-00000.csv");
            String exportCmd = "EXPORT('" + exportPathTrain + "', false, null, null, null, null) SELECT " + exportColumns.toString() + " FROM " + trainTable;
            exportData(conn, exportCmd, exportPathTrain + "/* ", exportPathTrain + "/traindata.txt");        
            
            //Get test_data_path
            String exportPathTest = dataDir + "/test";
            inputDict.addProperty("test_data_path", exportPathTest + "/part-r-00000.csv");
            exportCmd = "EXPORT('" + exportPathTest + "', false, null, null, null, null) SELECT " + exportColumns.toString() + " FROM " + testTable;
            exportData(conn, exportCmd, exportPathTest + "/* ", exportPathTest + "/testdata.txt");

            //Build JSON
            Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().setFieldNamingPolicy(FieldNamingPolicy.UPPER_CAMEL_CASE).create();
            String jsonData = gson.toJson(inputDict);
            
            LOG.error("JSON Data: " + jsonData);
                        
            ProcessBuilder pb = new ProcessBuilder("python",fullModelPath,
                    "--model_type=" + type,
                    "--model_dir=" + modelOutputDir,
                    "--inputs=" + jsonData);
            Process p = pb.start();
             
            BufferedReader bfr = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = "";
            LOG.error("Running Python starts: " + line);
            int exitCode = p.waitFor();
            LOG.error("Exit Code : "+exitCode);
            line = bfr.readLine();
            LOG.error("First Line: " + line);
            while ((line = bfr.readLine()) != null){
                LOG.error("Python Output: " + line);
            }

        }catch(Exception e){
                LOG.error("Exception calling pythong script.", e);
        }
    }
}