package ranking;

import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.jena.query.QuerySolution;
import org.apache.log4j.Logger;

import tools.SparqlQuerier;
import tools.Wikidata;

public class GiniGenerator {
	
	private static Logger logger = Logger.getLogger(GiniGenerator.class);
	
	private LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(100000);

	public GiniGenerator() {
	}
	
	public void explore() {
		String queryStr = "select ?property (count(*) as ?number) where {?s ?property ?o} GROUP BY ?property";
		try {
			new SparqlQuerier(queryStr, Wikidata.endpoint) {
				
				@Override
				public boolean fact(QuerySolution qs) throws InterruptedException {
					if (qs.get("property").isURIResource() && qs.get("number").isLiteral()) {
						String property = qs.get("property").toString();
						long number = qs.getLiteral("number").getLong();
						if (number >= 100 && property.startsWith("http://www.wikidata.org/prop/direct/")) // && property.endsWith("P532"))
							queue.add(property);
					}
					return true;
				}
				
				@Override
				public void end() {
				}
				
				@Override
				public void begin() {
				}
			}.execute();
		} catch (InterruptedException e) {
			logger.error(e, e);
		}
		for (int i = 0; i < 0; i++)
			try {
				String element = queue.take();
				logger.debug("skip " + element);
			} catch (InterruptedException e) {
				logger.warn(e);
			}
		ArrayList<ExplorerThread> threads = new ArrayList<>();
		for (int i = 0; i < 4; i++)
			threads.add(new ExplorerThread());
		for (ExplorerThread et : threads)
			et.start();
		try {
			for (ExplorerThread et : threads)
				et.join();
		} catch (InterruptedException e) {
			logger.error(e, e);
		}
	}
	
	public class ExplorerThread extends Thread {

		@Override
		public void run() {
			logger.info("start thread " + getName());
			while (queue.size() > 0) {
				String property = "";
				try {
					property = queue.take();
					logger.debug(property + " (" + queue.size() + ")");
					GiniAnalyzer analyzer = new GiniAnalyzer(Wikidata.endpoint, property);
					analyzer.analyze();
					analyzer.integrate();
					//analyzer.close();
				} catch (InterruptedException e) {
					logger.warn(e);
				}
			}
			logger.info("stop thread " + getName());
		}
	
	}


}
