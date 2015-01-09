package org.fiteagle.core.orchestrator.provision;

import java.util.HashMap;
import java.util.Map;

import org.fiteagle.core.tripletStoreAccessor.QueryExecuter;
import org.fiteagle.core.tripletStoreAccessor.TripletStoreAccessor.ResourceRepositoryException;

import com.hp.hpl.jena.query.QuerySolution;
import com.hp.hpl.jena.query.ResultSet;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.vocabulary.RDF;

public class HandleProvision {
  
  public static final Map<String, Object> getReservations(String group) throws ResourceRepositoryException{
    final Map<String, Object> reservations = new HashMap<>();
    
    String query = "PREFIX omn: <http://open-multinet.info/ontology/omn#> "
        + "SELECT ?reservationId ?componentManagerId WHERE { "
        + "<" + group + "> a omn:Group ." 
        + "?sliver omn:partOf \"" + group +"\" . "
        + "?reservationId omn:reserveInstanceFrom ?componentManagerId "
        + "}";
    ResultSet rs  = QueryExecuter.executeSparqlSelectQuery(query);
    
    while(rs.hasNext()){
      QuerySolution qs = rs.next();
      if (qs.contains("reservationId") && qs.contains("componentManagerId")) {
         reservations.put(qs.getResource("reservationId").getURI(), qs.getLiteral("componentManagerId").getString());
      }
    }
    return reservations;
  }
  
  public static Model createRequest(final Map<String, Object> reservations) throws ResourceRepositoryException{
    Model createModel = ModelFactory.createDefaultModel();
    for (Map.Entry<String, Object> instance : reservations.entrySet()) {
      Resource resourceAdapter = createModel.createResource(instance.getValue().toString());
      resourceAdapter.addProperty(RDF.type, getResourceAdapterName(instance.getValue()));
      Resource resource = createModel.createResource(instance.getKey());
      resource.addProperty(RDF.type, getResourceName(instance.getValue()));
    }
    return createModel;
  }

  private static String getResourceAdapterName(Object componentManagerId) throws ResourceRepositoryException{
    String query = "PREFIX omn: <http://open-multinet.info/ontology/omn#> "
        + "SELECT ?resourceAdapter WHERE { "
        + "<" + componentManagerId + "> a ?resourceAdapter ."
        + "?resourceName omn:implementedBy ?resourceAdapter" 
        + "}";
    ResultSet rs  = QueryExecuter.executeSparqlSelectQuery(query);
    String resourceName = "";
    while(rs.hasNext()){
      QuerySolution qs = rs.next();
      resourceName = qs.getResource("resourceAdapter").getURI();
    }
    return resourceName;
  }
  
  private static String getResourceName (Object componentManangerId) throws ResourceRepositoryException{
    String query = "PREFIX omn: <http://open-multinet.info/ontology/omn#> "
        + "SELECT ?resourceName WHERE { "
        + "<" + componentManangerId + "> a ?class ." 
        + "?resourceName omn:implementedBy ?class "
        + "}";
    ResultSet rs  = QueryExecuter.executeSparqlSelectQuery(query);
    String resourceName = "";
    while(rs.hasNext()){
      QuerySolution qs = rs.next();
      resourceName = qs.getResource("resourceName").getURI();
    }
    return resourceName;
  }
}
