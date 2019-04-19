/**
 * 
 */
package bobby.sfdc.prototype;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.http.HttpException;
import org.apache.http.client.ClientProtocolException;

import bobby.sfdc.prototype.oauth.AuthenticationException;
import bobby.sfdc.prototype.oauth.AuthenticationHelper;
import bobby.sfdc.prototype.oauth.json.OAuthTokenSuccessResponse;
import bobby.sfdc.prototype.util.CommandlineHelper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import bobby.sfdc.prototype.bulkv1.CloseV1Job;
import bobby.sfdc.prototype.bulkv1.CreateV1Batch;
import bobby.sfdc.prototype.bulkv1.CreateV1Job;
import bobby.sfdc.prototype.bulkv1.GetV1BatchInfo;
import bobby.sfdc.prototype.bulkv1.GetV1BatchResultContent;
import bobby.sfdc.prototype.bulkv1.GetV1BatchResultsList;
import bobby.sfdc.prototype.bulkv1.json.BulkV1BatchInfo;
import bobby.sfdc.prototype.bulkv1.json.BulkV1BatchList;
import bobby.sfdc.prototype.bulkv1.json.BulkV1BatchResultList;
import bobby.sfdc.prototype.bulkv1.json.BulkV1JobResponse;
import bobby.sfdc.prototype.bulkv2.*;
import bobby.sfdc.prototype.bulkv2.json.*;


/**
 * Commandline Interface to execute Bulk API 2.0 jobs
 * 
 * @author bobby.white
 *
 */
public class BulkMaster  {
	public static final String DEFAULT_LOGIN_URL = "https://login.salesforce.com";
	private static final String CONSUMER_KEY_PROP = "consumer.key";
	private static final String CONSUMER_SECRET_PROP = "consumer.secret";
	private static final String LOGIN_URL_PROP = "login.url";

	private Gson _gson;
	private String _authToken=null;
	private String _instanceUrl=null;
	private boolean _isInitialized=false;
	private String consumerKey=null;
	private String consumerSecret=null;
	private String loginUrl=null;
	private String _id;
	private Commands currentCommand=Commands.LIST; // Default Command
	private String jobId=null;
	private String objectName=null;
	private String externalIdFieldName=null;
	private String inputFileName="";
	private String outputDir="."; // Default to "here"
	private int pollingInterval=0;
	private String queryString;
	private boolean pkChunkingEnabled=false;
	private static  Logger _logger = Logger.getLogger(BulkMaster.class.getName());
	
	public static final String DATE_INPUT_PATTERN="yyyy-MM-dd'T'HH:mm:ss.SSSZZ";

	public static void main(String[] args) {
		BulkMaster mgr = new BulkMaster();
		try {
			if (args.length < 2) {
				CommandlineHelper.printSyntaxStatement();
				throw new IllegalArgumentException("Missing required command line arguments");
			}
			String userId = args[0];
			String password = args[1];
			
			String loginUrl = args.length >=3 ? args[2] : DEFAULT_LOGIN_URL;
			if (!loginUrl.startsWith("https:")) {
				loginUrl = DEFAULT_LOGIN_URL;
			}
			
			// Order is important here because of overrides
			mgr.initConnectedAppFromConfigFile("/connectedapp.properties");
			mgr.setOptionsFromCommandlineFlags(args);		
			mgr.setLoginUrl(loginUrl);

			
		
			mgr.getAuthToken(userId, password);

			System.out.println("Instance URL:" + mgr.getInstanceUrl());
			
			mgr.executeCommand();
			
		} catch (Throwable t) {
			_logger.log(Level.SEVERE,t.getMessage());
		}
		
	}

	private void setOptionsFromCommandlineFlags(String[] args) {
		CommandlineHelper helper = new CommandlineHelper(this);
		helper.setOptionsFromCommandlineFlags(args);
	}

	public Commands getCurrentCommand() {
		return currentCommand;
	}

	public void setCurrentCommand(Commands currentCommand) {
		this.currentCommand = currentCommand;
	}

	public String getJobId() {
		return jobId;
	}

	public void setJobId(String jobId) {
		this.jobId = jobId;
	}

	public String getObjectName() {
		return objectName;
	}

	public void setObjectName(String objectName) {
		this.objectName = objectName;
	}

	public String getExternalIdFieldName() {
		return externalIdFieldName;
	}

	public void setExternalIdFieldName(String externalIdFieldName) {
		this.externalIdFieldName = externalIdFieldName;
	}

	public String getInputFileName() {
		return inputFileName;
	}

	public void setInputFileName(String inputFileName) {
		this.inputFileName = inputFileName;
	}

	public String getOutputDir() {
		return outputDir;
	}

	public void setOutputDir(String outputDir) {
		this.outputDir = outputDir;
	}

	public int getPollingInterval() {
		return pollingInterval;
	}

	public void setPollingInterval(int pollingInterval) {
		this.pollingInterval = pollingInterval;
	}

	public String getQueryString() {
		return queryString;
	}

	public void setQueryString(String queryString) {
		this.queryString = queryString;
	}

	public boolean isPkChunkingEnabled() {
		return pkChunkingEnabled;
	}

	public void setPkChunkingEnabled(boolean pkChunkingEnabled) {
		this.pkChunkingEnabled = pkChunkingEnabled;
	}

	private void executeCommand() throws Throwable {
		
		switch(this.currentCommand) {
		case LIST:
			GetAllJobsResponse jobs = listJobsCommand();
			System.out.println("Jobs" + jobs);
			break;
		case INSERT:
			{
				CreateJobResponse result = createJobCommand(objectName,"insert","");
				uploadFileOperation(result.contentUrl,inputFileName);
				closeJobCommand(result.id,CloseJobRequest.UPLOADCOMPLETE);
				pollForResults(result.id);				
			}
			break;
		case UPSERT:
			{
				CreateJobResponse result = createJobCommand(objectName,"upsert",this.externalIdFieldName);
				uploadFileOperation(result.contentUrl,inputFileName);
				closeJobCommand(result.id,CloseJobRequest.UPLOADCOMPLETE);
				pollForResults(result.id);
			}
			break;
		case DELETE:
			{
				CreateJobResponse result = createJobCommand(objectName,"hardDelete","");
				uploadFileOperation(result.contentUrl,inputFileName);
				closeJobCommand(result.id,CloseJobRequest.UPLOADCOMPLETE);
				pollForResults(result.id);
			}
			break;
		case RESULTS:
			pollForResults(this.jobId);
			break;
		case STATUS:
			System.out.println(getStatusCommand(jobId));
			break;
		case CLOSEJOB:
			System.out.println(closeJobCommand(jobId,CloseJobRequest.UPLOADCOMPLETE));
			break;
		case ABORTJOB:
			System.out.println(closeJobCommand(jobId,CloseJobRequest.ABORTED));
			break;
		case QUERY:
			System.out.println(executeQueryCommand(objectName,queryString));	
			break;
		default:
			break;
		}

					
		
	}

	/**
	 * If pollingInterval is greater than zero, poll for this job to complete
	 * @param jobId
	 * @throws InterruptedException 
	 * @throws AuthenticationException 
	 * @throws IOException 
	 * @throws URISyntaxException 
	 * @throws ClientProtocolException 
	 */
	private JobInfo pollForResults(String jobId) throws InterruptedException, ClientProtocolException, URISyntaxException, IOException, AuthenticationException {
		JobInfo info=null;
		if (this.pollingInterval > 0) {
			// Loop until an exception or job completion

			GetJobInfo getter = new GetJobInfo(getInstanceUrl(),getAuthToken());

			do {
				_logger.info("sleeping " + pollingInterval + " seconds");
				Thread.sleep(pollingInterval * 1000);
				info = getter.execute(jobId); 
				_logger.info(info.toString());
				_logger.info("Is Running?: " + info.isRunning());
			} while (info.isRunning());
			
			if (info!= null && info.isComplete()) {
				_logger.info(getResultsCommand(info.id, GetJobResults.RESULTKIND.SUCCESS,outputDir));
				_logger.info(getResultsCommand(info.id, GetJobResults.RESULTKIND.FAILED,outputDir));
				_logger.info(getResultsCommand(info.id, GetJobResults.RESULTKIND.UNPROCESSED,outputDir));
			} else {
				_logger.info(info == null ? "Unable to get job results " : info.toString());
			}

			
		}
		return info;
		
	}

	private String getResultsCommand(String jobId, GetJobResults.RESULTKIND kind, String outputDir) throws ClientProtocolException, URISyntaxException, IOException, AuthenticationException {
		return new GetJobResults(getInstanceUrl(),getAuthToken()).execute(jobId,kind,outputDir);
	}

	private void uploadFileOperation(String contentUrl, String inputFileName) throws URISyntaxException, ClientProtocolException, IOException, AuthenticationException {
		UploadJob upload = new UploadJob(getInstanceUrl(),getAuthToken());
		upload.execute(contentUrl,inputFileName);
	}

	private CreateJobResponse createJobCommand(String objectName, String operation, String externalIdFieldName) throws Throwable {
		CreateJob creator = new CreateJob(getInstanceUrl(),getAuthToken());
		return creator.execute(objectName,operation,externalIdFieldName);
	}
	
	/**
	 * Execute a Bulk API V1 Query
	 * @param objectName
	 * @param query
	 * @return
	 * @throws Throwable
	 */
	private BulkV1JobResponse executeQueryCommand(String objectName, String query) throws Throwable {
		
		CreateV1Job creator = new CreateV1Job(getInstanceUrl(),getAuthToken());
		BulkV1JobResponse job = creator.execute("query",objectName,this.pkChunkingEnabled);
		
		// Create the Batch with the actual Query in it
		CreateV1Batch batcher = new CreateV1Batch(getInstanceUrl(),getAuthToken());
		batcher.execute(job.id,query);
				
		// Get a list of batches in the Job
		GetV1BatchInfo batchInfo = new GetV1BatchInfo(getInstanceUrl(),getAuthToken());
		
		// Download all of the results
		downloadV1BatchResults(job, batchInfo);
		
		// Close the Job
		CloseV1Job jobCloser = new CloseV1Job(getInstanceUrl(),getAuthToken());
		BulkV1JobResponse closeJobResult = jobCloser.execute(job.id);
				
		return closeJobResult;
	}

	protected void downloadV1BatchResults(BulkV1JobResponse job, GetV1BatchInfo batchInfo) throws URISyntaxException,
			ClientProtocolException, IOException, AuthenticationException, InterruptedException {
		boolean needToWait;
		Map<String,String> resultsAlreadyProcessed = new HashMap<String,String>();
		
		do {
			// Reset this flag that will keep us looping
			needToWait=false;
			
			// Get the current status
			BulkV1BatchList batches = batchInfo.execute(job.id);

			// Iterate through the batches and get their individual status
			for (BulkV1BatchInfo current : batches.batches) {
				_logger.info("Current batch: " + current);
				if (current.isRunning()) {
					// Need to come back and revisit this later
					needToWait = true;
				} else {
					// Fetch the results immediately
					BulkV1BatchResultList resultsList = new GetV1BatchResultsList(getInstanceUrl(),getAuthToken()).execute(job.id, current.id);
					for (String resultId : resultsList.results) {
						if (!resultsAlreadyProcessed.containsKey(resultId)) {
							_logger.info("Fetching resultId: " + resultId + " for batchId: " + current.id + " for job: " + job.id);
							new GetV1BatchResultContent(getInstanceUrl(),getAuthToken()).execute(job.id, current.id, resultId, outputDir);
							// Avoid downloading a batch result that we've already processed!
							resultsAlreadyProcessed.put(resultId,resultId);
						}
					}
				}
			}
			if (needToWait) {
				_logger.info("Waiting for " + this.pollingInterval + " seconds");
				Thread.sleep(this.pollingInterval * 1000);
			}
		} while (needToWait);
	}


	/**
	 * Get the Bulk API Jobs information
	 * 
	 * @throws Throwable 
	 */
	public GetAllJobsResponse listJobsCommand() throws Throwable {
		return new GetAllJobs(getInstanceUrl(),getAuthToken()).execute();
	}
	
	/**
	 * Get Bulk API Job Status information for a single Job
	 * 
	 * @throws Throwable 
	 */
	public JobInfo getStatusCommand(final String jobId) throws Throwable {
		return new GetJobInfo(getInstanceUrl(),getAuthToken()).execute(jobId);
	}
	
	/**
	 * Close an existing Bulk API V2 Job - either UploadCompleted or Aborted
	 * 
	 * @throws Throwable 
	 */
	public JobInfo closeJobCommand(String jobId, String subCommand) throws Throwable {
		return new CloseJob(getInstanceUrl(),getAuthToken()).execute(jobId, subCommand);
	}




	/**
	 * Define the Commands this processor implements
	 * @author bobby.white
	 *
	 */
	public enum Commands {
		LIST,
		INSERT,
		UPSERT,
		DELETE,
		STATUS,
		CLOSEJOB,
		ABORTJOB,
		RESULTS,
		QUERY
	}
	
	public enum Flags {
		LIST("l","List Jobs"),
		INSERT("i","Insert Records"),
		UPSERT("u","Upsert Records"),
		DELETE("d","Delete Records"),
		STATUS("s","Get Job Status"),
		CLOSEJOB("c","Close Job"),
		ABORTJOB("a","Abort Job"),
		RESULTS("r","Get Job results"),
		JOBID("j","Hex ID of the Job",true), 
		INPUTFILE("f","Input filename",true), 
		OUTPUTDIR("D","Output Directory",true),
		EXTERNALID("x","External ID fieldname for Upsert",true),
		OBJECTNAME("o","Object name for Insert, Update, or Delete",true),
		POLL("p","Poll for results - interval in seconds",true),
		QUERY("q","SOQL Query string",true),
		PKCHUNKING("pk","Enable PK Chunking for Large Queries");
		
		final private String label;
		final private String description;
		final private boolean requiresValue;

		Flags(String label, String description) {
		this.label=label;
		this.description=description;
		this.requiresValue=false;
		}
		Flags(String label, String description, boolean requiresValue) {
		this.label=label;
		this.description=description;
		this.requiresValue=requiresValue;
		}
		public String getLabel() {
			return label;
		}
		/**
		 * @return the description
		 */
		public String getDescription() {
			return description;
		}
		public boolean getRequiresValue() {
			return requiresValue;
		}
		/**
		 * Check to set if this flag is set
		 * @param arg
		 * @return
		 */
		public boolean isFlagSet(String arg) {
			return ("-" + getLabel()).compareTo(arg)==0;
		}
		/**
		 * Test to see if this argument is a valid flag
		 * @param arg
		 * @return true if it matches any defined flag
		 */
		public static boolean isValidFlag(String arg) {
			for (Flags current : values()) {
				if (current.isFlagSet(arg)) return true;
			}
			return false;
		}
	}

	
	/**
	 * Obtain the AuthToken using the Userid/Password flow
	 * 
	 * Assumes that the connection info has already been initialized
	 * 
	 * @param userName in the form  user1@company.com
	 * @param password you may be required to append your Security Token to your password
	 * @throws IllegalStateException
	 * @throws IOException 
	 * @throws HttpException 
	 * @throws AuthenticationException 
	 */
	public void getAuthToken(String userName, String password) throws IllegalStateException, AuthenticationException, HttpException, IOException {
		if (this.loginUrl==null) {
			throw new IllegalStateException("loginUrl must be initialized!");
		}
		if (this.consumerKey==null) {
			throw new IllegalStateException("consumerKey must be initialized!");
		}
		if (this.consumerSecret==null) {
			throw new IllegalStateException("consumerSecret must be initialized!");
		}


		OAuthTokenSuccessResponse result = new AuthenticationHelper().getAuthTokenUsingPasswordFlow(this.loginUrl, userName, password, this.consumerKey, this.consumerSecret);
		setAuthToken(result);
	}
	
	/**
	 * Set the Internal Members from the OAuthTokenSuccessResponse
	 * 
	 * @param auth  initialized by an OAuth based flow
	 */
	public void setAuthToken(OAuthTokenSuccessResponse auth) {
		this.setIsInitialized(true);
		this._authToken = auth.getAccess_token();
		this._id = auth.getId();
		this._instanceUrl = auth.getInstance_url();
	}

	/**
	 * Initialize Connection Information from the Properties File
	 * @param fileName
	 * @throws IOException 
	 * @throws FileNotFoundException 
	 */
	public void initConnectedAppFromConfigFile(String fileName) throws FileNotFoundException, IOException {
		Properties props = new Properties();
		props.load(BulkMaster.class.getResourceAsStream(fileName));
		this.consumerKey = (String) props.getProperty(CONSUMER_KEY_PROP);
		this.consumerSecret = (String) props.getProperty(CONSUMER_SECRET_PROP);
		this.loginUrl = (String) props.getProperty(LOGIN_URL_PROP);
	}
	
	
	/**
	 * Default constructor
	 */
	public BulkMaster() {
		registerGsonDeserializers();
	}

		
	
	public String getUserID() {
		String userId="";
		
		userId = _id.substring(_id.lastIndexOf('/')+1);
		
		return userId;
	}

	/**
	 * 
	 * @param templateURL - URL must include %parameterName%
	 * @param parameterName  - this must be in the URL, will be replaced with value
	 * @param value - the value to be substituted
	 * @return
	 */
	public static String getURLFromURLTemplate(
			String templateURL, String parameterName,
			String value) {
		
		if (templateURL == null) {
			throw new IllegalArgumentException("Template URL must not be null!");
		}
		if (parameterName == null) {
			throw new IllegalArgumentException("parameterName must not be null!");
		}
		if (value == null) {
			throw new IllegalArgumentException("value must not be null!");
		}
		
		String marker = "%" + parameterName + "%";
		if (!templateURL.contains(marker)) {
			throw new IllegalArgumentException("Template (" + templateURL + ") must contain " + marker);
		}
		
		return templateURL.replace(marker, value);
	}	

	private void registerGsonDeserializers() {
		GsonBuilder gsonBuilder = new GsonBuilder();
		gsonBuilder.setPrettyPrinting();
//		gsonBuilder.registerTypeAdapter(FeedElementPage.class, new FeedElementPageDeserializer());
		_gson = gsonBuilder.create();
	}

	protected Gson getGson() {
		return _gson;
	}

	protected String getAuthToken() {
		return _authToken;
	}

	protected void setAuthToken(String authToken) {
		this._authToken = authToken;
	}

	protected String getInstanceUrl() {
		return _instanceUrl;
	}

	protected void setInstanceUrl(String instanceUrl) {
		this._instanceUrl = instanceUrl;
	}

	protected boolean isInitialized() {
		return _isInitialized;
	}

	protected void setIsInitialized(boolean isInitialized) {
		this._isInitialized = isInitialized;
	}

	/**
	 * @return the consumerKey
	 */
	public String getConsumerKey() {
		return consumerKey;
	}

	/**
	 * @param consumerKey the consumerKey to set
	 */
	public void setConsumerKey(String consumerKey) {
		this.consumerKey = consumerKey;
	}

	/**
	 * @return the consumerSecret
	 */
	public String getConsumerSecret() {
		return consumerSecret;
	}

	/**
	 * @param consumerSecret the consumerSecret to set
	 */
	public void setConsumerSecret(String consumerSecret) {
		this.consumerSecret = consumerSecret;
	}

	/**
	 * @return the loginUrl
	 */
	public String getLoginUrl() {
		return loginUrl;
	}

	/**
	 * @param loginUrl the loginUrl to set
	 */
	public void setLoginUrl(String loginUrl) {
		this.loginUrl = loginUrl;
	}

}
