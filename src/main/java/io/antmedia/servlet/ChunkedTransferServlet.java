package io.antmedia.servlet;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.catalina.connector.ClientAbortException;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.web.context.ConfigurableWebApplicationContext;
import org.springframework.web.context.WebApplicationContext;

import io.antmedia.servlet.cmafutils.AtomParser;
import io.antmedia.servlet.cmafutils.AtomParser.MockAtomParser;
import io.antmedia.servlet.cmafutils.ICMAFChunkListener;
import io.antmedia.servlet.cmafutils.IParser;


public class ChunkedTransferServlet extends HttpServlet {


	public static final String STREAMS = "/streams";
	public static final String WEBAPPS = "webapps";
	protected static Logger logger = LoggerFactory.getLogger(ChunkedTransferServlet.class);


	public static class ChunkListener implements ICMAFChunkListener {

		public final AsyncContext asyncContext;
		public final IChunkedCacheManager cacheManager;
		public final String filePath;



		public ChunkListener(AsyncContext asyncContext, IChunkedCacheManager cacheManager, String filePath) {
			this.asyncContext = asyncContext;
			this.cacheManager = cacheManager;
			this.filePath = filePath;
		}

		@Override
		public void chunkCompleted(byte[] completeChunk) {

			if (completeChunk != null) 
			{
				try {
					ServletOutputStream oStream = asyncContext.getResponse().getOutputStream();
					oStream.write(completeChunk);
					oStream.flush();
				}
				catch (ClientAbortException e) {
					logger.warn("Client aborted - Removing chunklistener this client for file: {}", filePath);
					cacheManager.removeChunkListener(filePath, this);									
				}
				catch (Exception e) {
					logger.error(ExceptionUtils.getStackTrace(e));

				}
			}
			else 
			{
				logger.debug("context is completed for {}", filePath);
				//if it's null, it means related cache is finished
				asyncContext.complete();
			}

		}

	}

	@Override
	protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {		
		handleStream(req, resp);
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		handleStream(req, resp);
	}

	public void handleStream(HttpServletRequest req, HttpServletResponse resp) {
		ConfigurableWebApplicationContext appContext = (ConfigurableWebApplicationContext) req.getServletContext().getAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE);

		if (appContext != null && appContext.isRunning()) 
		{
			String applicationName = appContext.getApplicationName();

			String filepath = WEBAPPS + applicationName + STREAMS + req.getPathInfo();

			File finalFile = new File(filepath);

			String tmpFilepath = filepath + ".tmp"; 
			File tmpFile = new File(tmpFilepath);

			File streamsDir = new File(WEBAPPS + applicationName + STREAMS);
			File firstParent = finalFile.getParentFile();
			File grandParent = firstParent.getParentFile();

			if (firstParent.equals(streamsDir) || grandParent.equals(streamsDir)) 
			{
				mkdirIfRequired(req, applicationName);

				try {
					IChunkedCacheManager cacheManager = (IChunkedCacheManager) appContext.getBean(IChunkedCacheManager.BEAN_NAME);
					logger.trace("doPut key:{}", finalFile.getAbsolutePath());

					cacheManager.addCache(finalFile.getAbsolutePath());
					IParser atomparser;

					if (filepath.endsWith(".mpd") || filepath.endsWith(".m3u8")) 
					{
						//don't parse atom for mpd files because they are text files
						atomparser = new MockAtomParser();
					}
					else {
						atomparser = new AtomParser(completeChunk -> 
						cacheManager.append(finalFile.getAbsolutePath(), completeChunk)
								);
					}

					AsyncContext asyncContext = req.startAsync();

					InputStream inputStream = asyncContext.getRequest().getInputStream();
					asyncContext.start(() -> 
					readInputStream(finalFile, tmpFile, cacheManager, atomparser, asyncContext, inputStream)
							);
				}
				catch (BeansException | IllegalStateException | IOException e) 
				{
					logger.error(ExceptionUtils.getStackTrace(e));
					writeInternalError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, null);
				} 

			}
			else {
				logger.info("AppContext is not running for write request to {}", req.getRequestURI());
			}

		}
		else 
		{
			logger.info("AppContext is not running for write request to {}", req.getRequestURI());
			writeInternalError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Server is not ready. It's likely starting. Please try a few seconds later. ");

		}
	}

	private void mkdirIfRequired(HttpServletRequest req, String applicationName) 
	{
		int secondSlashIndex = req.getPathInfo().indexOf('/', 1);
		if (secondSlashIndex != -1) {
			String subDirName = req.getPathInfo().substring(0, secondSlashIndex);

			File subDir = new File( WEBAPPS + applicationName + STREAMS + subDirName);
			if (!subDir.exists()) {
				subDir.mkdir();
			}
		}
	}

	public void readInputStream(File finalFile, File tmpFile, IChunkedCacheManager cacheManager, IParser atomparser,
			AsyncContext asyncContext, InputStream inputStream) {
		try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
			byte[] data = new byte[2048];
			int length = 0;

			while ((length = inputStream.read(data, 0, data.length)) > 0) 
			{
				atomparser.parse(data, 0, length);
				fos.write(data, 0, length);
			}

			Files.move(tmpFile.toPath(), finalFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			asyncContext.complete();
		}
		catch (IOException e) 
		{
			logger.error(ExceptionUtils.getStackTrace(e));
		}
		finally {
			cacheManager.removeCache(finalFile.getAbsolutePath());
		}
		logger.trace("doPut done key:{}", finalFile.getAbsolutePath());
	}


	public void deleteRequest(HttpServletRequest req, HttpServletResponse resp) 
	{
		ConfigurableWebApplicationContext appContext = (ConfigurableWebApplicationContext) req.getServletContext().getAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE);

		if (appContext != null && appContext.isRunning()) 
		{
			String applicationName = appContext.getApplicationName();
			String filepath = WEBAPPS + applicationName + STREAMS + req.getPathInfo();

			File file = new File(filepath);


			File streamsDir = new File(WEBAPPS + applicationName + STREAMS);

			logger.debug("doDelete for file: {}", file.getAbsolutePath());
			try {
				if (file.exists()) 
				{
					//make sure streamsDir is parent of file
					File firstParent = file.getParentFile();
					File grandParent = firstParent.getParentFile();

					if (firstParent.equals(streamsDir) || grandParent.equals(streamsDir)) 
					{
						Files.deleteIfExists(file.toPath());
						deleteFreeDir(req, applicationName, streamsDir);

					}
					else 
					{
						logger.error("Parent or grant parent is not streams directory for DELETE operation {}", filepath);
						writeInternalError(resp, HttpServletResponse.SC_CONFLICT, null);
					}
				}
			}
			catch (Exception e) {
				writeInternalError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, null);
			}
		}
		else {
			logger.error("Server is not ready for req: {}",req.getPathInfo());
			writeInternalError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, null);
		}
	}

	@Override
	protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		deleteRequest(req, resp);
	}



	private void deleteFreeDir(HttpServletRequest req, String applicationName, File streamsDir) throws IOException {
		//delete the subdirectory if there is no file inside
		int secondSlashIndex = req.getPathInfo().indexOf('/', 1);
		if (secondSlashIndex != -1) 
		{
			String subDirName = req.getPathInfo().substring(0, req.getPathInfo().indexOf('/', 1));
			File subDir = new File( WEBAPPS + applicationName + STREAMS + subDirName);
			if (subDir.exists() && subDir.isDirectory() && subDir.getParentFile().equals(streamsDir) && subDir.list().length == 0) 
			{
				Files.deleteIfExists(subDir.toPath());
			}
		}
	}


	public void writeOutputStream(File file, AsyncContext asyncContext, OutputStream ostream ) 
	{
		try (FileInputStream fis = new FileInputStream(file)) {

			int length = 0;
			byte[] data = new byte[2048];

			while ((length = fis.read(data, 0, data.length)) > 0) {
				ostream.write(data, 0, length);
				ostream.flush();
			}

			asyncContext.complete();

		} 
		catch (IOException e) 
		{
			logger.error(ExceptionUtils.getStackTrace(e));
		}
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException 
	{
		handleGetRequest(req, resp);
	}
	
	
	public void handleGetRequest(HttpServletRequest req, HttpServletResponse resp) {
		ConfigurableWebApplicationContext appContext = (ConfigurableWebApplicationContext) req.getServletContext().getAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE);
		if (appContext != null && appContext.isRunning()) 
		{

			File file = new File(WEBAPPS + File.separator + req.getRequestURI());

			try {
				if (file.exists()) 
				{
					logger.trace("File exists: {}", file.getAbsolutePath());

					AsyncContext asyncContext = req.startAsync();
					ServletOutputStream outputStream = asyncContext.getResponse().getOutputStream();
					asyncContext.start(() -> writeOutputStream(file, asyncContext, outputStream));

				}
				else 
				{
					IChunkedCacheManager cacheManager = (IChunkedCacheManager) appContext.getBean(IChunkedCacheManager.BEAN_NAME);

					boolean cacheAvailable = cacheManager.hasCache(file.getAbsolutePath());

					if (cacheAvailable ) 
					{
						AsyncContext asyncContext = req.startAsync();
						cacheManager.registerChunkListener(file.getAbsolutePath(), new ChunkListener(asyncContext, cacheManager, file.getAbsolutePath()));
					}
					else 
					{
						logger.debug("Sending not found error(404) for {}", file.getAbsolutePath());
						writeInternalError(resp, HttpServletResponse.SC_NOT_FOUND, null);
					}

				}
			} 
			catch (BeansException | IllegalStateException | IOException e) 
			{
				logger.error(ExceptionUtils.getStackTrace(e));
				writeInternalError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, null);
			}
		}
		else 
		{
			logger.info("AppContext is not running for get request {}", req.getRequestURI());
			writeInternalError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Server is not ready. It's likely starting. Please try a few seconds later. ");
		}
	}

	private void writeInternalError(HttpServletResponse resp, int status, String message) {

		try {
			resp.setStatus(status);
			PrintWriter writer;
			writer = resp.getWriter();
			if (message != null) {
				writer.print(message);
			}
			writer.close();
		} catch (IOException e) {
			logger.error(ExceptionUtils.getStackTrace(e));
		}

	}








}
