package org.starexec.data.database;

import java.io.File;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.starexec.constants.R;
import org.starexec.data.to.Processor;
import org.starexec.data.to.Processor.ProcessorType;

/**
 * Handles all database interaction for bench, pre and post processors
 */
public class Processors {
	private static final Logger log = Logger.getLogger(Processors.class);
	
	/**
	 * Inserts a processor into the database
	 * @param processor The processor to add to the database
	 * @return The positive integer ID of the new processor if successful, -1 otherwise
	 * @author Tyler Jensen
	 */
	public static int add(Processor processor) {
		Connection con = null;			
		CallableStatement procedure = null;
		try {
			con = Common.getConnection();		
			
			 procedure = null;			
			procedure = con.prepareCall("{CALL AddProcessor(?, ?, ?, ?, ?, ?, ?)}");			
			procedure.setString(1, processor.getName());
			procedure.setString(2, processor.getDescription());
			procedure.setString(3, processor.getFilePath());
			procedure.setInt(4, processor.getCommunityId());
			procedure.setInt(5, processor.getType().getVal());
			procedure.setLong(6, FileUtils.sizeOf(new File(processor.getFilePath())));
			procedure.registerOutParameter(7, java.sql.Types.INTEGER);
			procedure.executeUpdate();
			
			int procId = procedure.getInt(7);	
			
			return procId;			
		} catch (Exception e){			
			log.error(e.getMessage(), e);		
		} finally {
			Common.safeClose(con);
			Common.safeClose(procedure);
		}
		
		return -1;
	}
	
	/**
	 * Deletes a given processor from a space
	 * @param processorId the id of the processor to delete
	 * @return True if the operation was a success, false otherwise
	 * @author Todd Elvers
	 */
	public static boolean delete(int processorId){
		Connection con = null;			
		File processorFile = null;
		CallableStatement procedure = null;
		try {
			con = Common.getConnection();
			 procedure = con.prepareCall("{CALL DeleteProcessor(?, ?)}");
			procedure.setInt(1, processorId);
			procedure.registerOutParameter(2, java.sql.Types.LONGNVARCHAR);
			procedure.executeUpdate();
			
			// Get processor_path of processor
			processorFile = new File(procedure.getString(2));
			log.debug(String.format("Removal of processor [id=%d] was successful.", processorId));
			
			// Try and delete file referenced by processor_path and its parent directory
			if(processorFile.delete()){
				log.debug(String.format("File [%s] was deleted at [%s] because it was no inter referenced anywhere.", processorFile.getName(), processorFile.getAbsolutePath()));
			}
			if(processorFile.getParentFile().delete()){
				log.debug(String.format("Directory [%s] was deleted because it was empty.", processorFile.getParentFile().getAbsolutePath()));
			}
			
			return true;
		} catch (Exception e){			
			log.error(e.getMessage(), e);		
		} finally {
			Common.safeClose(con);
			Common.safeClose(procedure);
		}
		
		log.debug(String.format("Removal of processor [id=%d] failed.", processorId));
		return false;
	}
	
	/**	 
	 * @param con The connection to make the query on
	 * @param processorId The id of the bench processor to retrieve
	 * @return The corresponding processor
	 * @author Tyler Jensen
	 */
	protected static Processor get(Connection con, int processorId) throws Exception {						
		CallableStatement procedure = null;
		ResultSet results = null;
					
		try {
			procedure = con.prepareCall("{CALL GetProcessorById(?)}");
			procedure.setInt(1, processorId);
			results = procedure.executeQuery();
			if(results.next()){							
				Processor t = new Processor();
				t.setId(results.getInt("id"));
				t.setCommunityId(results.getInt("community"));
				t.setDescription(results.getString("description"));
				t.setName(results.getString("name"));
				t.setFilePath(results.getString("path"));
				t.setType(ProcessorType.valueOf(results.getInt("processor_type")));
				t.setDiskSize(results.getLong("disk_size"));
				return t;					
			}
		} catch (Exception e) {
			log.error("Processors.get says "+e.getMessage(),e);
		} finally {
			Common.safeClose(results);
			Common.safeClose(procedure);
		}
									
		
		return null;
	}
	
	/**	 
	 * @param processorId The id of the bench processor to retrieve
	 * @return The corresponding processor
	 * @author Tyler Jensen
	 */
	public static Processor get(int processorId) {
		Connection con = null;			
		
		try {
			con = Common.getConnection();					
			return Processors.get(con, processorId);			
		} catch (Exception e){			
			log.error(e.getMessage(), e);		
		} finally {
			Common.safeClose(con);
		}
		
		return null;
	}
	
	/**
	 * Gets the list of processors
	 * @param type The type of processors to filter by
	 * @return the list of processors
	 * @author Todd Elvers
	 */
	public static List<Processor> getAll(ProcessorType type){
		Connection con = null;			
		CallableStatement procedure = null;
		ResultSet results = null;
		try {
			con = Common.getConnection();		
			 procedure = con.prepareCall("{CALL GetAllProcessors(?)}");
			procedure.setInt(1, type.getVal());
			 results = procedure.executeQuery();
			List<Processor> processors = new LinkedList<Processor>();
			
			while(results.next()){
				Processor bt = new Processor();
				bt.setId(results.getInt("id"));
				bt.setName(results.getString("name"));
				bt.setDescription(results.getString("description"));
				bt.setFilePath((results.getString("path")));
				bt.setCommunityId((results.getInt("community")));
				bt.setType(ProcessorType.valueOf(results.getInt("processor_type")));
				bt.setDiskSize(results.getLong("disk_size"));
				processors.add(bt);
			}
			
			return processors;
		} catch (Exception e){			
			log.error(e.getMessage(), e);		
		} finally {
			Common.safeClose(con);
			Common.safeClose(procedure);
			Common.safeClose(results);
		}
		
		return null;
	}	
	
	
	/**	 
	 * @param communityId The id of the community to retrieve all processors for
	 * @param type The type of processors to get for the community
	 * @return A list of all processors of the given type that the community owns
	 * @author Tyler Jensen
	 */
	public static List<Processor> getByCommunity(int communityId, ProcessorType type) {
		Connection con = null;			
		CallableStatement procedure = null;
		ResultSet results = null;
		try {
			con = Common.getConnection();					
			 procedure = con.prepareCall("{CALL GetProcessorsByCommunity(?, ?)}");
			procedure.setInt(1, communityId);
			procedure.setInt(2, type.getVal());
			 results = procedure.executeQuery();
			List<Processor> processors = new LinkedList<Processor>();
			
			while(results.next()){							
				Processor t = new Processor();
				t.setId(results.getInt("id"));
				t.setCommunityId(results.getInt("community"));
				t.setDescription(results.getString("description"));
				t.setName(results.getString("name"));
				t.setFilePath(results.getString("path"));
				t.setType(ProcessorType.valueOf(results.getInt("processor_type")));
				t.setDiskSize(results.getLong("disk_size"));
				processors.add(t);						
			}				
			
			return processors;
		} catch (Exception e){			
			log.error(e.getMessage(), e);		
		} finally {
			Common.safeClose(con);
			Common.safeClose(procedure);
			Common.safeClose(results);
		}
		
		return null;
	}	
	
	/**	 
	 * Gets all processors that a user can see because they share a community
	 * @param userId-- the user to retrieve post processors for
	 * @param type The type of processors to get
	 * @return A list of all unique processors of the given type that the user can see
	 * @author Eric Burns
	 */
	public static List<Processor> getByUser(int userId, ProcessorType type) {
		Connection con = null;			
		CallableStatement procedure = null;
		ResultSet results = null;
		try {
			con = Common.getConnection();					
			 procedure = con.prepareCall("{CALL GetProcessorsByUser(?, ?)}");
			procedure.setInt(1, userId);
			procedure.setInt(2, type.getVal());
			 results = procedure.executeQuery();
			List<Processor> processors = new LinkedList<Processor>();
			
			while(results.next()){							
				Processor t = new Processor();
				t.setId(results.getInt("id"));
				t.setName(results.getString("name"));
				processors.add(t);						
			}				
			return processors;
		} catch (Exception e){			
			log.error(e.getMessage(), e);		
		} finally {
			Common.safeClose(con);
			Common.safeClose(procedure);
			Common.safeClose(results);
		}
		
		return null;
	}	

	/**
	 * Updates the description of a processor with the given processor id
	 * @param processorId the id of the processor to update
	 * @param newDesc the new description to update the processor with
	 * @return True if the operation was a success, false otherwise
	 * @author Tyler Jensen
	 */
	public static boolean updateDescription(int processorId, String newDesc){
		Connection con = null;			
		CallableStatement procedure = null;
		try {
			con = Common.getConnection();		
			 procedure = con.prepareCall("{CALL UpdateProcessorDescription(?, ?)}");
			procedure.setInt(1, processorId);					
			procedure.setString(2, newDesc);
			
			procedure.executeUpdate();			
			return true;			
		} catch (Exception e){			
			log.error(e.getMessage(), e);		
		} finally {
			Common.safeClose(con);
			Common.safeClose(procedure);
		}
		
		return false;
	}
	
	/**
	 * Updates the name of a processor with the given processor id
	 * 
	 * @param processorId the id of the processor to update
	 * @param newName the new name to update the processor with
	 * @return True if the operation was a success, false otherwise
	 * @author Tyler Jensen
	 */
	public static boolean updateName(int processorId, String newName){
		Connection con = null;			
		CallableStatement procedure = null;
		try {
			con = Common.getConnection();		
			 procedure = con.prepareCall("{CALL UpdateProcessorName(?, ?)}");
			procedure.setInt(1, processorId);					
			procedure.setString(2, newName);
			
			procedure.executeUpdate();			
			return true;			
		} catch (Exception e){			
			log.error(e.getMessage(), e);		
		} finally {
			Common.safeClose(con);
			Common.safeClose(procedure);
		}
		
		return false;
	}	
		
}