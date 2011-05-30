package org.cipango.console;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.ReflectionException;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.jrobin.core.RrdDb;
import org.jrobin.core.RrdDbPool;
import org.jrobin.core.RrdDef;
import org.jrobin.core.RrdDefTemplate;
import org.jrobin.core.RrdException;
import org.jrobin.core.Sample;
import org.jrobin.graph.RrdGraph;
import org.jrobin.graph.RrdGraphDef;
import org.jrobin.graph.RrdGraphDefTemplate;
import org.xml.sax.InputSource;

public class StatisticGraph
{

	public static final String TYPE_CALLS = "calls";
	public static final String TYPE_MEMORY = "memory";
	public static final String TYPE_MESSAGES = "messages";

	private static final String RDD_TEMPLATE_FILE_NAME = "rddTemplate.xml";
	private static final String RDD_CALLS_GRAPH_TEMPLATE = "rddCallsGraphTemplate.xml";
	private static final String RDD_MEMORY_GRAPH_TEMPLATE = "rddMemoryGraphTemplate.xml";
	private static final String RDD_MESSAGES_GRAPH_TEMPLATE = "rddMessagesGraphTemplate.xml";

	private RrdGraphDefTemplate _callGraphTemplate;
	private RrdGraphDefTemplate _memoryGraphTemplate;
	private RrdGraphDefTemplate _messagesGraphTemplate;
	private long _refreshPeriod = -1; // To ensure that the stat will start if
										// needed at startup

	private StatisticGraphTask _task;
	private RrdDbPool _rrdPool;
	private String _rrdPath;
	private MBeanServerConnection _connection;
	private String _dataFileName;
	private ObjectName _sessionManger;

	private Timer _statTimer = new Timer("Statistics timer");
	private static Runtime __runtime = Runtime.getRuntime();

	private Logger _logger = Log.getLogger("console");

	private boolean _started = false;

	public StatisticGraph(MBeanServerConnection connection) throws AttributeNotFoundException,
			InstanceNotFoundException, MBeanException, ReflectionException, IOException, RrdException
	{
		_connection = connection;
		_sessionManger = (ObjectName) _connection.getAttribute(ConsoleFilter.SERVER, "sessionManager");
		_rrdPool = RrdDbPool.getInstance();
	}

	/**
	 * Sets the refresh period for statistics in seconds. If the period has changed, write
	 * statictics immediatly and reschedule the timer with <code>statRefreshPeriod</code>.
	 * 
	 * @param statRefreshPeriod The statistics refresh period in seconds or <code>-1</code> to
	 *            disabled refresh.
	 */
	public void setRefreshPeriod(long statRefreshPeriod)
	{
		if (_refreshPeriod != statRefreshPeriod)
		{
			this._refreshPeriod = statRefreshPeriod;
			if (_task != null)
			{
				_task.run();
				_task.cancel();
			}

			_task = new StatisticGraphTask();

			if (_refreshPeriod != -1)
			{
				_statTimer.schedule(_task, _refreshPeriod * 1000, _refreshPeriod * 1000);
			}
		}
	}
	
	public void reset()
	{
		try
		{
			RrdDb rrdDb = _rrdPool.requestRrdDb(_rrdPath);
			Sample sample = rrdDb.createSample();
			sample.setValue("calls", (Integer) _connection.getAttribute(_sessionManger, "callSessions"));
			long totalMemory = __runtime.totalMemory();
			sample.setValue("maxMemory", __runtime.maxMemory());
			sample.setValue("totalMemory", totalMemory);
			sample.setValue("usedMemory", totalMemory - __runtime.freeMemory());
			// No values are set for incomingMessages, outgoingMessages to reset these counters.
			sample.update();
			_rrdPool.release(rrdDb);
		}
		catch (Exception e)
		{
			_logger.warn("Unable to set statistics", e);
		}
	}

	public void updateDb()
	{
		try
		{
			RrdDb rrdDb = _rrdPool.requestRrdDb(_rrdPath);
			Sample sample = rrdDb.createSample();
			sample.setValue("calls", (Integer) _connection.getAttribute(_sessionManger, "callSessions"));
			long totalMemory = __runtime.totalMemory();
			sample.setValue("maxMemory", __runtime.maxMemory());
			sample.setValue("totalMemory", totalMemory);
			sample.setValue("usedMemory", totalMemory - __runtime.freeMemory());
			if (_connection.isRegistered(ConsoleFilter.CONNECTOR_MANAGER))
			{
				sample.setValue("incomingMessages",
						(Long) _connection.getAttribute(ConsoleFilter.CONNECTOR_MANAGER, "messagesReceived"));
				sample.setValue("outgoingMessages",
						(Long) _connection.getAttribute(ConsoleFilter.CONNECTOR_MANAGER, "messagesSent"));
			}
			sample.update();
			_rrdPool.release(rrdDb);
		}
		catch (Exception e)
		{
			_logger.warn("Unable to set statistics", e);
		}
	}

	public byte[] createGraphAsPng(Date start, Date end, RrdGraphDefTemplate graphTemplate)
	{
		try
		{
			RrdDb rrdDb = _rrdPool.requestRrdDb(_rrdPath);
			_rrdPool.release(rrdDb);
			graphTemplate.setVariable("start", start);
			graphTemplate.setVariable("end", end);
			graphTemplate.setVariable("rrd", _rrdPath);
			// create graph finally
			RrdGraphDef gDef = graphTemplate.getRrdGraphDef();
			RrdGraph graph = new RrdGraph(gDef);
			return graph.getRrdGraphInfo().getBytes();
		}
		catch (Exception e)
		{
			_logger.warn("Unable to create graph", e);
			return null;
		}
	}
	
	/**
	 * Create a graph of the last <code>time</code> seconds.
	 * 
	 * @param time
	 * @return The PNG image.
	 */
	public byte[] createGraphAsPng(long time, String type)
	{
		long start = System.currentTimeMillis() - time * 1000;
		long end = System.currentTimeMillis() - 5000; // Remove last 5 seconds due to bug with Jrobin LAST function
		return createGraphAsPng(new Date(start), new Date(end), getTemplate(type));
	}

	public void setDataFileName(String name)
	{
		_dataFileName = name;
	}

	public void start() throws Exception
	{
		if (_started)
			return;
		try
		{
			if (_dataFileName == null)
				_dataFileName = System.getProperty("jetty.home", ".") + "/logs/statistics.rdd";

			File rrdFile = new File(_dataFileName);
			_rrdPath = rrdFile.getAbsolutePath();

			if (!rrdFile.exists())
			{
				InputStream templateIs = getClass().getResourceAsStream(RDD_TEMPLATE_FILE_NAME);

				RrdDefTemplate defTemplate = new RrdDefTemplate(new InputSource(templateIs));

				defTemplate.setVariable("path", _rrdPath);
				defTemplate.setVariable("start", new Date(System.currentTimeMillis()));
				RrdDef rrdDef = defTemplate.getRrdDef();

				RrdDb rrdDb = _rrdPool.requestRrdDb(rrdDef);
				rrdDb.getRrdDef().getStep();
				_rrdPool.release(rrdDb);
			}

			InputStream templateGraph = getClass().getResourceAsStream(RDD_CALLS_GRAPH_TEMPLATE);
			_callGraphTemplate = new RrdGraphDefTemplate(new InputSource(templateGraph));
			templateGraph = getClass().getResourceAsStream(RDD_MEMORY_GRAPH_TEMPLATE);
			_memoryGraphTemplate = new RrdGraphDefTemplate(new InputSource(templateGraph));
			templateGraph = getClass().getResourceAsStream(RDD_MESSAGES_GRAPH_TEMPLATE);
			_messagesGraphTemplate = new RrdGraphDefTemplate(new InputSource(templateGraph));

			updateDb();

			RrdDb rrdDb = _rrdPool.requestRrdDb(_rrdPath);
			setRefreshPeriod(rrdDb.getRrdDef().getStep());
			_rrdPool.release(rrdDb);
			_started = true;
		}
		catch (Exception e)
		{
			_logger.warn("Unable to create RRD", e);
		}
	}

	public boolean isStarted()
	{
		return _started;
	}

	public void stop()
	{
		_started = false;
		if (_task != null)
			_task.cancel();
	}

	private RrdGraphDefTemplate getTemplate(String type)
	{
		if (TYPE_MEMORY.equalsIgnoreCase(type))
			return _memoryGraphTemplate;
		else if (TYPE_CALLS.equalsIgnoreCase(type))
			return _callGraphTemplate;
		else if (TYPE_MESSAGES.equalsIgnoreCase(type))
			return _messagesGraphTemplate;
		else
		{
			_logger.warn("Unknown graph type: " + type);
			return _callGraphTemplate;
		}
	}

	class StatisticGraphTask extends TimerTask
	{

		public void run()
		{
			updateDb();
		}

	}

}
