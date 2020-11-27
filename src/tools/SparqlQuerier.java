package tools;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Properties;

import org.apache.jena.atlas.web.HttpException;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.sparql.engine.http.QueryEngineHTTP;
import org.apache.log4j.Logger;

public abstract class SparqlQuerier {
	
	private static Logger logger = Logger.getLogger(SparqlQuerier.class);

	protected int limit = 10000;
	protected int offset = 0;
	protected String query;
	protected String triplestore;
	protected String timeout = null;
	protected SparqlStatus status = SparqlStatus.WAIT;
	protected QueryExecution qexec = null;
	
	protected static int readTimeout = 3 * 60 * 1000;
	protected static int connectTimeout = 1 * 60 * 1000;

	public abstract void begin();
	public abstract void end();	
	public abstract boolean fact(QuerySolution qs) throws InterruptedException;
	
	private static long lastTime = 0;
	public static long fairUseDelay = 100;
	
	public enum SparqlStatus {
		WAIT,
		BEGIN,
		SEND,
		RECEIVE,
		END
	};
	
	public static void configure(String filename) {
		Properties properties = new Properties();
		try {
			properties.load(new FileInputStream(filename));
		} catch (FileNotFoundException e) {
			logger.error(e, e);
		} catch (IOException e) {
			logger.error(e, e);
		}
		readTimeout = Integer.parseInt(properties.getProperty("read_timeout")) * 1000;
		connectTimeout = Integer.parseInt(properties.getProperty("connect_timeout")) * 1000;
		fairUseDelay = Integer.parseInt(properties.getProperty("fair_use_delay"));
		logger.info("fairUseDelay " + fairUseDelay);
	}
	
	public static void setFairUseDelay(long d) {
		fairUseDelay = d;
	}
	
	public SparqlQuerier(String query, String triplestore) {
		this.query = query;
		this.triplestore = triplestore;
	}
	
	public synchronized static void checkTime() {
		//int q = (int) (Math.random() * 1000);
		long currentTime = System.currentTimeMillis();
		long currentDelay = currentTime - lastTime;
		//logger.trace("query " + q + " { " + currentDelay + " " + currentTime + " " + lastTime + " " + fairUseDelay);
		if (currentDelay < fairUseDelay) {
			try {
				lastTime = lastTime +  fairUseDelay;
				//logger.trace(q + " " + (fairUseDelay - currentDelay));
				Thread.sleep(fairUseDelay - currentDelay);
			} catch (InterruptedException e) {
				logger.error(e);
			}
		}
		else 
			lastTime = currentTime;
		//logger.trace("query " + q + " }");
	}
	
	public void execute() throws InterruptedException {
		execute(Integer.MAX_VALUE, 0);
	}
	
	public void execute(int pageLimit) throws InterruptedException {
		execute(pageLimit, 0);
	}
		
	public void execute(int pageLimit, int offset) throws InterruptedException {
		try {
		status = SparqlStatus.BEGIN;
		begin();
		//offset = 0;
		int k = 0;
		int pageNb = 0;
		do {
			pageNb++;
			k = 0;
			String queryStr = "" 
					+ query
					+ " LIMIT " + limit
 					+ " OFFSET " + (limit * offset);
			Query query = QueryFactory.create(queryStr);
			if (fairUseDelay > 0) {
				checkTime();
			}
			int queryId = (int) (Math.random() * 1000); 
			logger.debug("query " + triplestore + ": (" + queryId + ") " + queryStr);
			status = SparqlStatus.SEND;
			qexec = QueryExecutionFactory.sparqlService(triplestore, query);
			qexec.setTimeout(readTimeout, connectTimeout);
			if (timeout != null)
				((QueryEngineHTTP) qexec).addParam("timeout", timeout);
			if (qexec != null) {
				long tempo = 10000;
				long sumTempo = 0;
				boolean done = false;
				ResultSet resultSet = null;
				do {
					try {
						resultSet = qexec.execSelect();
						done = true;
					} catch (Exception e) {
						logger.warn(e + " => " + tempo + " (" + queryId + ") " + queryStr);						
						if (e instanceof HttpException && ((HttpException)e).getResponseCode() == 429) {//(e.getResponseCode() == 429) {
							Thread.sleep(tempo);
							sumTempo +=  tempo;
							tempo = tempo * 2;
							if (tempo <= 400000) {
								if (sumTempo >= 40000 || (qexec != null && qexec.isClosed())) {
									logger.warn("renew query" + " (" + queryId + ")");
									close();
									qexec = QueryExecutionFactory.sparqlService(triplestore, QueryFactory.create(queryStr));
									qexec.setTimeout(readTimeout, connectTimeout);
									if (timeout != null)
										((QueryEngineHTTP) qexec).addParam("timeout", timeout);
									sumTempo = 0;
								}
								done = false;
							}
							else {
								logger.warn("abort query" + " (" + queryId + ")");
								done = true;
							}
						}
						else 
							done = true;
					}
				} while (!done);
				status = SparqlStatus.RECEIVE;
				if (resultSet != null) {
					while (resultSet.hasNext()) {
						QuerySolution qs = resultSet.nextSolution();
						if (qs != null) {
							fact(qs);
							k++;
						}
					}
				}
			}
			close();
			offset++;
		} while (k >= limit && pageNb < pageLimit);
		status = SparqlStatus.END;
		end();
		status = SparqlStatus.WAIT;
		} catch (Exception e) {
			close();
			logger.warn(triplestore + " " + e, e);
			if(e instanceof InterruptedException) {
				throw e;
			}
		}
	}
	
	public void close() {
		if (qexec != null && !qexec.isClosed()) {
			//logger.debug("close");
			qexec.abort();
			qexec.close();
			qexec = null;
		}
	}
	
	public void setLimit(int limit) {
		this.limit = limit;
	}
	public void setTimeout(String timeout) {
		this.timeout = timeout;
	}
	
	public SparqlStatus getStatus() {
		return status;
	}
	
	public void safeExecute(long millis) throws InterruptedException {
		Thread t = new Thread(new Runnable() {

			@Override
			public void run() {
					try {
						execute();
					} catch (InterruptedException e) {
						close();
						logger.info(e);
					}
			}
			
		});
		t.start();
		t.join(millis);
		if (getStatus() != SparqlStatus.SEND) {
			t.join();
		}
		else {
			close();
			t.interrupt();
		}
	}
	
	public static int getInDegree(String uri, String triplestore) {
		//return new SparqlQuerier.InDegreeQuery(uri, Wikidata.endpoint).getInDegree();
		return new SparqlQuerier("select (COUNT(*) AS ?number) where {?s ?p <" + uri + ">}", triplestore) {

			private int inDegree;

			@Override
			public void begin() {
			}

			@Override
			public void end() {
			}

			@Override
			public boolean fact(QuerySolution qs) throws InterruptedException {
				inDegree = qs.getLiteral("number").getInt();
				return true;
			}

			public int getInDegree() {
				try {
					execute();
				} catch (InterruptedException e) {
					logger.error(e, e);
				}
				return inDegree;
			}
			
		}.getInDegree();
	}
	
	public static String getLabel(String uri, String triplestore) {
		return new SparqlQuerier("select ?label where {<" + uri + "> <http://www.w3.org/2000/01/rdf-schema#label> ?label. FILTER(LANG(?label) = \"en\")}", triplestore) {

			private String label;

			@Override
			public void begin() {
			}

			@Override
			public void end() {
			}

			@Override
			public boolean fact(QuerySolution qs) throws InterruptedException {
				label = qs.getLiteral("label").getString();
				return true;
			}

			public String getLabel() {
				try {
					execute();
				} catch (InterruptedException e) {
					logger.error(e, e);
				}
				return label;
			}
			
		}.getLabel();
	}
	
	public static String getInverseLabel(String uri, String triplestore) {
		return new SparqlQuerier("select ?label where {<" + uri + "> <http://www.wikidata.org/prop/direct/P7087> ?o . ?o <http://www.w3.org/2000/01/rdf-schema#label> ?label. FILTER(LANG(?label) = \"en\")}", triplestore) {

			private String label;

			@Override
			public void begin() {
			}

			@Override
			public void end() {
			}

			@Override
			public boolean fact(QuerySolution qs) throws InterruptedException {
				label = qs.getLiteral("label").getString();
				return true;
			}

			public String getLabel() {
				try {
					execute();
				} catch (InterruptedException e) {
					logger.error(e, e);
				}
				return label;
			}
			
		}.getLabel();
	}
	
	public static String getDescription(String uri, String triplestore) {
		return new SparqlQuerier("select ?description where {<" + uri + "> <http://schema.org/description> ?description. FILTER(LANG(?description) = \"en\")}", triplestore) {

			private String description;

			@Override
			public void begin() {
			}

			@Override
			public void end() {
			}

			@Override
			public boolean fact(QuerySolution qs) throws InterruptedException {
				description = qs.getLiteral("description").getString();
				return true;
			}

			public String getDescription() {
				try {
					execute();
				} catch (InterruptedException e) {
					logger.error(e, e);
				}
				return description;
			}
			
		}.getDescription();
	}
	
	public static String getDomain(String uri, String triplestore) {
		return new SparqlQuerier("select * where {select ?c (count(*) as ?nb) where {select ?s ?c where {?s <" + uri + "> ?o . ?s <http://www.wikidata.org/prop/direct/P31> ?c} limit 1000} group by ?c order by desc(?nb) limit 1}", triplestore) {

			private String domain;

			@Override
			public void begin() {
			}

			@Override
			public void end() {
			}

			@Override
			public boolean fact(QuerySolution qs) throws InterruptedException {
				domain = qs.getResource("c").toString();
				return true;
			}

			public String getDomain() {
				try {
					execute();
				} catch (InterruptedException e) {
					logger.error(e, e);
				}
				return domain;
			}
			
		}.getDomain();
	}
	
	public static String getRange(String uri, String triplestore) {
		return new SparqlQuerier("select * where {select ?c (count(*) as ?nb) where {select ?s ?c where {?s <" + uri + "> ?o . ?o <http://www.wikidata.org/prop/direct/P31> ?c} limit 1000} group by ?c order by desc(?nb) limit 1}", triplestore) {

			private String range;

			@Override
			public void begin() {
			}

			@Override
			public void end() {
			}

			@Override
			public boolean fact(QuerySolution qs) throws InterruptedException {
				range = qs.getResource("c").toString();
				return true;
			}

			public String getRange() {
				try {
					execute();
				} catch (InterruptedException e) {
					logger.error(e, e);
				}
				return range;
			}
			
		}.getRange();
	}
	

	
}
