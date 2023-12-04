package ranking;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Properties;

import org.apache.jena.query.QuerySolution;
import org.apache.log4j.Logger;

import tools.SparqlQuerier;

public class GiniAnalyzer {
	
	private static Logger logger = Logger.getLogger(GiniAnalyzer.class);
	
	private ArrayList<Entity> numbers = new ArrayList<Entity>();
	private int min = Integer.MAX_VALUE;
	private int max = 0;
	private int sum = 0;
	private int pageExcerpt = 10;
	private PreparedStatement insertLorenzStatement; 
	private PreparedStatement insertLorenzEntity;
	private double gini;

	private String triplestore;

	private String property; 
	
	public class Entity {
		public int number;
		public String uri;
		
		public Entity(String uri, int number) {
			this.uri = uri;
			this.number = number;
		}
	}
	
	
	public GiniAnalyzer(String triplestore, String property) {
		this.triplestore = triplestore;
		this.property = property;
		Properties properties = new Properties();
		try {
			properties.load(new FileInputStream(Ranking.CONFIGURATION_FILE));
		} catch (FileNotFoundException e) {
			logger.error(e, e);
		} catch (IOException e) {
			logger.error(e);
		}
		//open(properties);
		addSparqlQuery(triplestore, property, pageExcerpt, 0, 0);
	}
	
	public int insertLorenz(String property) {
		int id = -1;
		try {
			insertLorenzStatement.setString(1, property);
			insertLorenzStatement.setDouble(2, gini);
			insertLorenzStatement.execute();
			ResultSet rs = insertLorenzStatement.getGeneratedKeys();
			if (rs.next())
				id = rs.getInt(1);
		} catch (SQLException e) {
			logger.error(e, e);
		}
		return id;
	}
	
	public void insertLorenzEntity(int id, String entity, int rank, int number) {
		try {
			insertLorenzEntity.setInt(1, id);
			insertLorenzEntity.setString(2, entity);
			insertLorenzEntity.setInt(3, rank);
			insertLorenzEntity.setInt(4, number);
			insertLorenzEntity.execute();
		} catch (SQLException e) {
			logger.error(e, e);
		}
	}
	
	public void addSparqlQuery(String triplestore, String property, int limit, int offset, int min) {
		String queryStr = "SELECT ?o (COUNT(*) AS ?number) WHERE {?s <" + property + "> ?o} GROUP BY ?o";
//		String queryStr = "SELECT ?o (COUNT(*) AS ?number) WHERE {?s <" + property + "> ?o} GROUP BY ?o HAVING (COUNT(*) >= " + min + ") ";
		try {
			new SparqlQuerier(queryStr, triplestore) {
				
				@Override
				public boolean fact(QuerySolution qs) throws InterruptedException {
					if (qs.get("o").isURIResource()) {
						int number = qs.getLiteral("number").getInt();
						numbers.add(new Entity(qs.get("o").toString(), number));
					}
					return true;
				}
				
				@Override
				public void end() {
				}
				
				@Override
				public void begin() {
				}
			}.execute(limit, offset);
		} catch (InterruptedException e) {
			logger.error(e, e);
		}
	}

	public void analyze() {
		logger.trace("sort");
		numbers.sort(new Comparator<Entity>() {
			@Override
			public int compare(Entity o1, Entity o2) {
				if (o2.number == o1.number)
					return o2.uri.compareTo(o1.uri);
				else
					return o1.number - o2.number;
			}
		});
		logger.trace("distinct");
		String previous = "";
		for (int k = 0; k < numbers.size(); k++) {
			if (previous.equals(numbers.get(k).uri)) {
				numbers.remove(k);
				k--;
			}
			else
				previous = numbers.get(k).uri;
		}
			
		logger.trace("compute statistics");
		max = 0;
		min = Integer.MAX_VALUE;
		sum = 0;
		for (Entity n : numbers) {
			//logger.trace(n.uri + " " + n.number);
			max = Integer.max(max, n.number);
			min = Integer.min(min, n.number);
			sum += n.number;
		}
		logger.trace("compute gini coefficient");
		double currentCumX = 0;
		double currentCumY = 0;
		double area = 0;
		double x = 1. / numbers.size();
		for (Entity n : numbers) {
			double y = (1. * n.number) / sum;
			currentCumX += x;
			area += currentCumY * x + y * x * 0.5;
			currentCumY += y;
			logger.trace(currentCumX + " " + currentCumY + " " + area + " " + n.number);
		}
		gini = (0.5 - area) /  0.5;
		//computeCritical();
		/*double num = 0;
		double den = 0;
		for (int i = 0; i < numbers.size(); i++) {
			Entity n = numbers.get(i);
			num += (i + 1) * n.number;
			den += n.number;
		}
		gini = 2. / numbers.size() * num / den - (numbers.size() + 1.) / numbers.size(); */
		logger.trace(numbers.size() + " " + sum + " " + min + " "  + max + " " + gini);
	}
	
	public boolean integrate() {
		if (gini >= 0.1 && numbers.size() >= 10) {
			if (numbers.size() == pageExcerpt * 10000) {
				numbers.clear();
				addSparqlQuery(triplestore, property, Integer.MAX_VALUE, 0, 0);
				analyze();
			}
			if (gini >= 0.1 && numbers.size() >= 10) {
				/*int id = insertLorenz(property);
				double previousNumber = Double.MAX_VALUE;
				int rank = 0;
				int k = 0;
				for (int i = numbers.size() - 1; i >= 0; i--) {
					Entity e = numbers.get(i);
					k++;
					if (e.number < previousNumber) {
						rank = k;
						previousNumber = e.number;
					}
					insertLorenzEntity(id, e.uri, rank, e.number);
				}*/
				logger.info(property + ";" + numbers.size() + ";" + sum + ";" + min + ";"  + max + ";" + gini);
				return true;
			}
		}
		logger.info(property + ";" + numbers.size() + ";" + sum + ";" + min + ";"  + max + ";" + gini);
		return false;
	}
	
	public ArrayList<Entity> getNumbers() {
		return numbers;
	}

	public int getMin() {
		return min;
	}

	public int getMax() {
		return max;
	}

	public int getSum() {
		return sum;
	}
	
	public double getGini() {
		return gini;
	}

	public void show() {
		for (int i = 0; i < 50; i++)
			logger.info(numbers.get(i).uri + " " + numbers.get(i).number);
	}

}
