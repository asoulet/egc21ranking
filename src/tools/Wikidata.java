package tools;

import org.apache.jena.query.QuerySolution;
import org.apache.log4j.Logger;

public class Wikidata {

	private static Logger logger = Logger.getLogger(Wikidata.class);

	public final static String endpoint = "https://query.wikidata.org/sparql";
	
	public static WikidataItem getWikipediaItem(String uri, String lang) {
		return new SparqlQuerier("select ?wp ?title where {?wp <http://schema.org/about> <" + uri + "> . ?wp <http://schema.org/isPartOf> <https://" + lang + ".wikipedia.org/>. ?wp <http://schema.org/name> ?title}", Wikidata.endpoint) {

			private WikidataItem item = null;

			@Override
			public void begin() {
			}

			@Override
			public void end() {
			}

			@Override
			public boolean fact(QuerySolution qs) throws InterruptedException {
				String wp = qs.getResource("wp").toString();
				String title = qs.getLiteral("title").getString();
				item = new WikidataItem(wp, title);
				return true;
			}

			public WikidataItem getWikidataItem() {
				try {
					execute();
				} catch (InterruptedException e) {
					logger.error(e, e);
				}
				return item;
			}
			
		}.getWikidataItem();
	}
	
}
