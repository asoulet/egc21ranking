package ranking;

import org.apache.log4j.Logger;

import tools.SparqlQuerier;

public class Ranking {
	
	private static Logger logger = Logger.getLogger(Ranking.class);

	public final static String NAME = "ranking";
	public final static String VERSION = "EGC21";
	public final static String CONFIGURATION_FILE = "properties/ranking.properties";

	public static void main(String[] args) {
		logger.info(NAME + " " + VERSION);
		SparqlQuerier.configure(CONFIGURATION_FILE);
		GiniGenerator generator = new GiniGenerator();
		generator.explore();
	}

}
