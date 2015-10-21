package org.fiteagle.core.reservation;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.jms.JMSContext;
import javax.jms.JMSException;
import javax.jms.Message;

import org.fiteagle.api.core.Config;
import org.fiteagle.api.core.IConfig;
import org.fiteagle.api.core.IMessageBus;
import org.fiteagle.api.core.MessageBusOntologyModel;
import org.fiteagle.api.core.MessageUtil;
import org.fiteagle.api.tripletStoreAccessor.TripletStoreAccessor;

import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.Property;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.ResIterator;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.rdf.model.SimpleSelector;
import com.hp.hpl.jena.rdf.model.Statement;
import com.hp.hpl.jena.rdf.model.StmtIterator;
import com.hp.hpl.jena.rdf.model.impl.StatementImpl;
import com.hp.hpl.jena.vocabulary.OWL;
import com.hp.hpl.jena.vocabulary.RDF;
import com.hp.hpl.jena.vocabulary.RDFS;

import info.openmultinet.ontology.exceptions.InvalidModelException;
import info.openmultinet.ontology.vocabulary.Omn;
import info.openmultinet.ontology.vocabulary.Omn_lifecycle;
import info.openmultinet.ontology.vocabulary.Omn_resource;

/**
 * Created by dne on 15.02.15.
 */
public class ReservationHandler {
    private static final Logger LOGGER = Logger.getLogger(ReservationHandler.class.getName());

    public Message handleReservation(Model requestModel, String serialization, String requestID, JMSContext context) throws TripletStoreAccessor.ResourceRepositoryException {

        LOGGER.log(Level.INFO, "START: handle reservation");
        Message responseMessage = null;
        LOGGER.log(Level.INFO, "Checking reservation request");
        String errorMessage = checkReservationRequest(requestModel);
        if(errorMessage == null || errorMessage.isEmpty()){
          Model reservationModel = ModelFactory.createDefaultModel();
          LOGGER.log(Level.INFO, "Creating reservation model");
          createReservationModel(requestModel, reservationModel);
          LOGGER.log(Level.INFO, "Reserving model");
          reserve(reservationModel);
          String serializedResponse = MessageUtil.serializeModel(reservationModel, serialization);
          responseMessage = MessageUtil.createRDFMessage(serializedResponse, IMessageBus.TYPE_INFORM, null, serialization, requestID, context);
          try {
            responseMessage.setStringProperty(IMessageBus.MESSAGE_SOURCE, IMessageBus.SOURCE_RESERVATION);
          } catch (JMSException e) {
            LOGGER.log(Level.SEVERE, e.getMessage());
          }
        }
        else {
          responseMessage = MessageUtil.createErrorMessage(errorMessage, requestID, context);
        }
        
        LOGGER.log(Level.INFO, "END: handle reservation");
        return responseMessage;
    }

    private Model createReservationModel(Model requestModel, Model reservationModel) {
        
      Map<String, Resource> resourcesIDs = new HashMap<String, Resource>();
      Model assistantModel = ModelFactory.createDefaultModel();
      
        ResIterator iterator = requestModel.listResourcesWithProperty(RDF.type, Omn.Topology);
        while (iterator.hasNext()) {
            Resource topology = iterator.nextResource();
            assistantModel.add(topology, RDF.type, Omn.Topology);
           if (TripletStoreAccessor.exists(topology.getURI())) {
               LOGGER.log(Level.INFO, "Topology already exists");
               Model topologyModel = TripletStoreAccessor.getResource(topology.getURI());
               
               ResIterator iter = topologyModel.listResourcesWithProperty(RDF.type, Omn.Topology);
               while(iter.hasNext()){
                 Resource topo = iter.nextResource();
                 if(topo.hasProperty(MessageBusOntologyModel.endTime)){
                   Statement endTimeStmt = topo.getProperty(MessageBusOntologyModel.endTime);
                   assistantModel.add(topo, endTimeStmt.getPredicate(), endTimeStmt.getString());
                 }
                 
                 if(topo.hasProperty(Omn_lifecycle.hasAuthenticationInformation)){
                   Statement hasAuthenticationInformationStmt = topo.getProperty(Omn_lifecycle.hasAuthenticationInformation);
                   assistantModel.add(topo, hasAuthenticationInformationStmt.getPredicate(), hasAuthenticationInformationStmt.getObject());
                 }
                 
                 if(topo.hasProperty(Omn_lifecycle.project)){
                   Statement project = topo.getProperty(Omn_lifecycle.project);
                   assistantModel.add(topo, project.getPredicate(), project.getObject());
                 }
               }

            }
        else {

               Resource newTopology = assistantModel.getResource(topology.getURI());
               Property property = assistantModel.createProperty(MessageBusOntologyModel.endTime.getNameSpace(), MessageBusOntologyModel.endTime.getLocalName());
               property.addProperty(RDF.type, OWL.FunctionalProperty);
               newTopology.addProperty(property, new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(getDefaultExpirationTime()));
               if (topology.getProperty(Omn_lifecycle.hasAuthenticationInformation) != null)
                   newTopology.addProperty(Omn_lifecycle.hasAuthenticationInformation, topology.getProperty(Omn_lifecycle.hasAuthenticationInformation).getObject());
               
               if(topology.hasProperty(Omn_lifecycle.project))
                 newTopology.addProperty(Omn_lifecycle.project, topology.getProperty(Omn_lifecycle.project).getObject());

           }

//            ResIterator resIter =  requestModel.listResourcesWithProperty(Omn.isResourceOf, topology);
            ResIterator resIter = requestModel.listSubjects();
            
            
            
            while(resIter.hasNext()){
                Resource resource = resIter.nextResource();
                
                if(resource.hasProperty(Omn.isResourceOf)){
                
                SimpleSelector selector= new SimpleSelector(resource, null,(Object) null);

                StmtIterator statementIter = requestModel.listStatements(selector);
                Resource newResource = assistantModel.createResource(resource.getNameSpace()+ UUID.randomUUID().toString());
                
                if(!resource.hasProperty(Omn_lifecycle.implementedBy)){
                  
                  newResource.addProperty(Omn_lifecycle.implementedBy, getAdapterForResource(requestModel, resource, assistantModel));
                  
                }

               resourcesIDs.put(resource.getURI(), newResource);
                
                while(statementIter.hasNext()){
                    Statement statement = statementIter.nextStatement();

                    newResource.addProperty(statement.getPredicate(), statement.getObject());
                    
                    
                    if(statement.getPredicate().equals(Omn_lifecycle.usesService)){
                        StmtIterator serviceModel =requestModel.listStatements(new SimpleSelector(statement.getObject().asResource(),null,(Object) null));
                        assistantModel.add(serviceModel);
                    }
               
                }
                assistantModel.add(topology, Omn.hasResource,newResource);
                
            } 
                else if(resource.hasProperty(Omn.hasResource)){
              
            } else {
                
//                if(resource.hasProperty(Omn.isResourceOf) || !resource.hasProperty(Omn.hasResource)){
                  StmtIterator stmtIterator = resource.listProperties();
                  while(stmtIterator.hasNext()){
                    Statement statement = stmtIterator.nextStatement();
                    assistantModel.add(statement);
                  }

                }
                
                
                
            }



        }

        
        ResIterator resIter = assistantModel.listSubjects();
        while(resIter.hasNext()){
          Resource res = resIter.nextResource();
          StmtIterator stmtIter = res.listProperties();
          while(stmtIter.hasNext()){
            Statement stmt = stmtIter.nextStatement();
            if("deployedOn".equals(stmt.getPredicate().getLocalName()) || "requires".equals(stmt.getPredicate().getLocalName())){
              Statement newStatement = new StatementImpl(stmt.getSubject(), stmt.getPredicate(), resourcesIDs.get(stmt.getObject().toString()));
              reservationModel.add(newStatement);
            }
            else{
              reservationModel.add(stmt);
            }
          }
        }
        
        

        
        return reservationModel;
    }

    
  private Resource getAdapterForResource(Model requestModel, Resource resource, Model reservationModel){
    
    Resource adapter = null;
    SimpleSelector typeSelector = new SimpleSelector(resource, RDF.type, (RDFNode) null);
    StmtIterator typeStatementIterator = requestModel.listStatements(typeSelector);
    Map<String, Model> cache = new HashMap<String, Model>();
    
    while(typeStatementIterator.hasNext()){
      
      Statement typeStatement = typeStatementIterator.next();
      Resource resourceType = typeStatement.getObject().asResource();
      String typeURI = resourceType.getURI();
      
      LOGGER.info("TODO: we should either not get the complete resource here or cache the result");
      Model adapterModel;
      if (cache.containsKey(typeURI))
	  adapterModel = cache.get(typeURI);
      else
	  adapterModel = TripletStoreAccessor.getResource(typeURI);
      
      if(modelHasProperty(adapterModel, resourceType)){
        
        LOGGER.info("Model has property");
        SimpleSelector adapterInstancesSelector = new SimpleSelector(null, Omn_lifecycle.canImplement, (RDFNode) null);
        StmtIterator adapterInstancesIterator = adapterModel.listStatements(adapterInstancesSelector);
        
        while(adapterInstancesIterator.hasNext()){
          LOGGER.info("Adapter instance has instance");
          Statement adapterInstanceStatement = adapterInstancesIterator.nextStatement();
          String resourceURI = adapterInstanceStatement.getObject().asResource().getURI();
          Resource adapterInstance = adapterInstanceStatement.getSubject();
          
          if(typeURI.equals(resourceURI) && checkResourceAdapterAvailability(adapterInstance)){
            if(isExclusive(requestModel, resource)){
              
              int reservedResources = getReservedResources(reservationModel, resourceType, adapterInstance);
                  
              if(adapterAbleToCreate(adapterInstanceStatement.getSubject().getURI(), resource, reservedResources+1)){
                adapter = adapterInstance;
                break;
              }
            }
            else {
              adapter = adapterInstance;
              break;
            }
          }
        }
      }
    }
    return adapter;
  }
  
  /**
   * This method counts reserved resources with the same type by certain adapter instance.
   * @param reservationModel
   * @param requestedResourceType
   * @param adapterInstance
   * @return
   */
  private int getReservedResources(Model reservationModel, Resource requestedResourceType, Resource adapterInstance){
    int reservedResources = 0;
    StmtIterator resourceIterator = reservationModel.listStatements(new SimpleSelector((Resource) null, Omn_lifecycle.implementedBy, adapterInstance));
    while(resourceIterator.hasNext()){
      Statement statement = resourceIterator.nextStatement();
      Resource resource = statement.getSubject();
      String reservedResourceType = resource.getProperty(RDF.type).getObject().asResource().getURI();
      if(requestedResourceType.getURI().equals(reservedResourceType)){
        reservedResources += 1;
      }
    }
    return reservedResources;
  }
  
  private boolean modelHasProperty(Model model, Resource value){
    if(!model.isEmpty() && model != null ){
      if(model.contains((Resource) null, Omn_lifecycle.canImplement, value)){
        return true;
      } else return false;
    } else return false;
      
  }

  /**
   * checks reservation request
   * @param requestModel
   * @return error message
   */
  private String checkReservationRequest(Model requestModel){
    
    final List<String> errorsList = new ArrayList<String>();
    final Set<String> cache = new HashSet<String>();
    final ResIterator resIterator = requestModel.listResourcesWithProperty(Omn.isResourceOf); 
    
    while (resIterator.hasNext()) {
      Resource resource = resIterator.nextResource();
      
      String uri = resource.getURI();
      LOGGER.info("Checking resource adapter instance: " + uri);
  
      String implURI = resource.getProperty(Omn_lifecycle.implementedBy).getObject().asResource().getURI();
      LOGGER.info("Processing: " + implURI);
      if (cache.contains(implURI)) {
	  LOGGER.info("CACHE: HIT");
	  continue;
      } else {
	  LOGGER.info("CACHE: MISS");
	  cache.add(implURI);
      }
      
      checkResourceAdapterInstance(resource, requestModel, errorsList);
      
      if(isExclusive(requestModel, resource)){
	  LOGGER.info("Checking resource exclusivity");
        checkExclusiveResource(resource, requestModel, errorsList);
        }
      }
    return getErrorMessage(errorsList);
  }
  
  /**
   * converts errors list to a string 
   * @param errorsList
   * @return
   */
  private String getErrorMessage(final List<String> errorsList){
    String errorMessage = "";
    if(!errorsList.isEmpty()){
      for (String error : errorsList){
        errorMessage += error + " .";
      }
    }
      return errorMessage;
  }
  
  
  private void checkExclusiveResource(Resource resource, Model requestModel, final List<String> errorsList) {
    
    if (resource.hasProperty(Omn_lifecycle.implementedBy)) {
      
      Object adapterInstance = resource.getProperty(Omn_lifecycle.implementedBy).getObject();
      
      if(checkResourceAdapterAvailability(adapterInstance)){
        int sameResFromSameAdpater = getNumOfSameResFromSameAdapter(resource, requestModel, adapterInstance);
        
        if (!adapterAbleToCreate(adapterInstance, resource, sameResFromSameAdpater)) {
          errorsList.add(" Requested resource is exclusive. Adapter instance can't handle resources more than its limit");
        }
      } else 
        errorsList.add(" Requested component id is not available");

      
    } else { // requested resource without componentID
      List<Resource> adapterInstancesList = getAdapterInstancesList(resource, requestModel);
      if (!adapterInstancesList.isEmpty()) {
        int numberOfSameResourceType = getNumberOfSameResourceType(resource, requestModel);
        LOGGER.log(Level.INFO, "Number of requested resources from the same type is " + numberOfSameResourceType);
        
        if(!checkAdaptersAbility(adapterInstancesList, resource, numberOfSameResourceType)){
          errorsList.add(" No available component ID has been found to support the requested resource "
              + resource.getLocalName());
        }
      }
    }
  }
  
  /**
   * this method checks if a list of potential adapters can create the number of the requested resources.
   * @param adapterInstancesList
   * @param requestedResource
   * @param numberOfSameResources
   * @return
   */
  private boolean checkAdaptersAbility(List<Resource> adapterInstancesList, Resource requestedResource, int numberOfSameResources){
    boolean adapterAbleToCreate = false;
    int rest = numberOfSameResources;
    for(Resource adapterInstance : adapterInstancesList){
      rest = rest - getAdapterAbility(adapterInstance, requestedResource);
    }
    if(rest <= 0) 
      adapterAbleToCreate = true;
    return adapterAbleToCreate;
    
  }
  
  /**
   * this methods check reservation request and calculate the number of resources with same resource type.
   * @param requestedResource
   * @param requestModel
   * @return
   */
  private int getNumberOfSameResourceType(Resource requestedResource, Model requestModel){
    RDFNode resourceType = getResourceType(requestedResource);
    StmtIterator stmtIterator = requestModel.listStatements(new SimpleSelector((Resource) null, RDF.type, resourceType));
    int numberOfSameResources = stmtIterator.toList().size();
    
    return numberOfSameResources;
  }
  
  private int getNumOfSameResFromSameAdapter(Resource requestedResource, Model requestModel, Object adapterInstance){
    int sameResFromSameAdapter = 0;
    RDFNode resourceType = getResourceType(requestedResource);
    SimpleSelector resourceSelector = new SimpleSelector((Resource) null, RDF.type, resourceType);
    StmtIterator resourceStatementIterator = requestModel.listStatements(resourceSelector);
    while (resourceStatementIterator.hasNext()){
      Statement resourceStatement = resourceStatementIterator.nextStatement();
      Resource resource = resourceStatement.getSubject();
      if (resource.hasProperty(Omn_lifecycle.implementedBy)) {
        Object adapter = resource.getProperty(Omn_lifecycle.implementedBy).getObject();
        if(adapterInstance.equals(adapter)){
          sameResFromSameAdapter += 1;
        }
      }
    }
    
    return sameResFromSameAdapter;
  }
  
  /**
   * checks if the adapter instance can create a new resource
   * @param adapterInstance
   * @param resource
   * @return
   */
  private boolean adapterAbleToCreate(Object adapterInstance, Resource resource, int numberOfRequestedResources){
    
    Model model = ModelFactory.createDefaultModel();
    Resource adapter = model.createResource(adapterInstance.toString());
    int adapterAbility = getAdapterAbility(adapter, resource);
    if(adapterAbility >= numberOfRequestedResources)
      return true;
    else return false;
    
  }
  
  /**
   * this method calculate the number of resources which the adapter instance currently can create.
   * @param adapterInstance
   * @param requestedResource
   * @return
   */
  private int getAdapterAbility(Resource adapterInstance, Resource requestedResource){
    Model adapterInstanceModel = TripletStoreAccessor.getResource(adapterInstance.getURI());
    int maxInstances = getMaxInstances(adapterInstanceModel);
    int handledResourcesNum = gethandledResourcesNum(requestedResource, adapterInstanceModel);
    if((maxInstances - handledResourcesNum) < 0)
      return 0;
    else return (maxInstances - handledResourcesNum);
    
  }
  
  /**
   * this method look in DB for reserved and provisioned instances by the adapter instance.
   * @param resource
   * @param adapterInstance
   * @return the number of reserved and provisioned instances.
   */
  private int gethandledResourcesNum(Resource requestedResource, Model adapterInstanceModel){
    
    List<Resource> resourcesList = getResourcesList(adapterInstanceModel);
    
    if(!resourcesList.isEmpty()){
      return checkResourcesList(resourcesList, requestedResource);
    } else return 0;
    
  }
  

  /**
   * counts only handled resources which have the same name as requested resource 
   * @param resourcesList
   * @param requestedResource
   * @return
   */
  private int checkResourcesList(List<Resource> resourcesList, Resource requestedResource){
    int matchedResourcesNum = 0;
    RDFNode requestedResourceType = getResourceType(requestedResource);
    for(Resource resource : resourcesList){
      Model resourceModel = TripletStoreAccessor.getResource(resource.getURI());
      
      SimpleSelector selector = new SimpleSelector(resource, RDF.type, (RDFNode) null);
      StmtIterator stmtIterator = resourceModel.listStatements(selector);
      while(stmtIterator.hasNext()){
        Statement statement = stmtIterator.nextStatement();
        if(requestedResourceType.equals(statement.getObject())){
          matchedResourcesNum += 1;
        }
      }
    }
    return matchedResourcesNum;
  }
  
  
  /**
   * this method returns back a list of resources URIs which are reserved and provisioned by the adapter instance
   * @param adapterInstance
   * @param resource
   * @return list of resources
   */
  private List<Resource> getResourcesList(Model adapterInstanceModel){
    List<Resource> resourcesList = new ArrayList<Resource>();
    if(adapterInstanceModel.contains((Resource) null, Omn_lifecycle.implementedBy, (RDFNode) null)){
      SimpleSelector selector = new SimpleSelector((Resource) null, Omn_lifecycle.implementedBy, (RDFNode) null);
      StmtIterator stmtIterator = adapterInstanceModel.listStatements(selector);
      while(stmtIterator.hasNext()){
        Statement statement = stmtIterator.nextStatement();
        resourcesList.add(statement.getSubject());
      }
    }
    
    return resourcesList;
  }
  
  private Model getResourceAdapterModel(Object adapterInstance){
    Model mo = ModelFactory.createDefaultModel();
    Resource re = mo.createResource(adapterInstance.toString());
    Model model = TripletStoreAccessor.getResource(re.getURI());
    
    return model;
  }
  
  /**
   * This method is to give back the maximum instances which an adapter instance can provide.
   * @param model
   * @return
   */
  private int getMaxInstances(Model model){
    if(model.contains((Resource) null, MessageBusOntologyModel.maxInstances, (RDFNode) null)){
       return (int) model.getProperty((Resource) null, MessageBusOntologyModel.maxInstances).getLong();
    }
    else return 1;
  }
  
  
  private boolean isExclusive(Model requestModel, Resource resource){
    SimpleSelector selector = new SimpleSelector(resource, Omn_resource.isExclusive, (RDFNode) null);
    StmtIterator stmtIterator = requestModel.listStatements(selector);
    while(stmtIterator.hasNext()){
      Statement statement = stmtIterator.nextStatement();
      if(statement.getBoolean()){
        return true;
      }
    }
    return false;
    
  }
  
  
  private void checkResourceAdapterInstance(Resource resource, Model requestModel, final List<String> errorList){
    
    RDFNode type = getResourceType(resource);
    
    if (resource.hasProperty(Omn_lifecycle.implementedBy)) {
      Object adapterInstance = resource.getProperty(Omn_lifecycle.implementedBy).getObject();
      LOGGER.info("Checking resource adapter type");
      checkResourceAdapterType(type, adapterInstance, errorList);
      LOGGER.info("Checking resource adapter availability");
      if(!checkResourceAdapterAvailability(adapterInstance)){
        String errorMessage = "the requested component_id " + adapterInstance.toString() + "is not available";
        errorList.add(errorMessage);
      }
    }
    else {
	LOGGER.info("Checking if resource's type is supported by any adapter instance");
      List<Resource> adapterInstancesList = getAdapterInstancesList(resource, requestModel);
      if(adapterInstancesList.isEmpty()){
        String errorMessage = "The requested resource " + resource.getLocalName() + " is not supported";
        errorList.add(errorMessage);
      }
    }
  }
  
  /**
   * this method looks for adapter instances supporting requested resource type.
   * @param resource
   * @param requestModel
   * @return
   */
  private List<Resource> getAdapterInstancesList(Resource resource, Model requestModel) {
    List<Resource> adapterInstancesList = new ArrayList<Resource>();
    
    SimpleSelector typeSelector = new SimpleSelector(resource, RDF.type, (RDFNode) null);
    StmtIterator typeStatementIterator = requestModel.listStatements(typeSelector);
    
    while(typeStatementIterator.hasNext()){
      Statement typeStatement = typeStatementIterator.next();
      String uri = typeStatement.getObject().asResource().getURI();
      
      LOGGER.info("Getting resource: " + uri);
      Model model = TripletStoreAccessor.getResource(uri);
      
      if(!model.isEmpty() && model != null && model.contains((Resource) null, Omn_lifecycle.canImplement, typeStatement.getObject().asResource())){
        ResIterator adapterInstanceIter = model.listResourcesWithProperty(Omn_lifecycle.canImplement, typeStatement.getObject());
        
        while(adapterInstanceIter.hasNext()){
          Resource adapterInstance = adapterInstanceIter.nextResource();
          if(checkResourceAdapterAvailability(adapterInstance)){
            adapterInstancesList.add(adapterInstance);
          }
        }
      }
    }
    
    return adapterInstancesList;
  }
  
  /**
   * checks if resource type is supported by the requested adapter instance
   * @param type
   * @param adapterInstance
   * @param errorMessage
   */
  private void checkResourceAdapterType(RDFNode type, Object adapterInstance, final List<String> errorList){
    
    Model mo = ModelFactory.createDefaultModel();
    Resource re = mo.createResource(adapterInstance.toString());
    //fixme: do not describe complete resource, ask instead for "Omn_lifecycle.canImplement type"
    Model model = TripletStoreAccessor.getResource(re.getURI());
    if (model == null || model.isEmpty()) {
      errorList.add("The requested component id " + re.getURI() + " is not supported");
    } else 
      if(!model.contains(re, Omn_lifecycle.canImplement, type)){
        String errorMessage = "The requested sliver type " + type.toString()
            + " is not supported. Please see supported sliver types";
        errorList.add(errorMessage); 
      }
  }
  
  /**
   * checks if the adapter instance is available
   * @param adapterInstance
   * @param errorList
   */
  private boolean checkResourceAdapterAvailability(Object adapterInstance){
   
    boolean available = true;
    Model model = getResourceAdapterModel(adapterInstance);
    if(model.contains((Resource) null, Omn_resource.isAvailable, (RDFNode) null)){
        Statement statement = model.getProperty((Resource)null, Omn_resource.isAvailable);
        available = statement.getBoolean();
      }
    return available;
  }
  
  /**
   * 
   * @param resource
   * @return the type of the resource
   */
  private RDFNode getResourceType(Resource resource){
    RDFNode type = null;
    if (resource.hasProperty(RDF.type)) {
      
      StmtIterator stmtIterator = resource.listProperties(RDF.type);
      while (stmtIterator.hasNext()) {
        
        Statement statement = stmtIterator.nextStatement();
        if (!Omn_resource.Node.equals(statement.getObject())) {
          type = statement.getObject();
        }
      }
    }
    return type;
  }
  
    
    public void reserve(Model model) {


        ResIterator resIterator = model.listResourcesWithProperty(Omn.isResourceOf);
        while(resIterator.hasNext()){

            Resource requestedResource = resIterator.nextResource();
            Config config =new Config();
            Resource reservation = model.createResource(config.getProperty(IConfig.LOCAL_NAMESPACE).concat("reservation/")+ UUID.randomUUID().toString());
            reservation.addProperty(RDFS.label, reservation.getURI());
            reservation.addProperty(RDF.type,Omn.Reservation);
            requestedResource.addProperty(Omn.hasReservation, reservation);
            reservation.addProperty(Omn.isReservationOf, requestedResource);
            Date afterAdding2h = getDefaultExpirationTime();
            reservation.addProperty(MessageBusOntologyModel.endTime, new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(afterAdding2h));
            reservation.addProperty(Omn_lifecycle.hasReservationState, Omn_lifecycle.Allocated);
            Property property  = model.createProperty(Omn_lifecycle.hasState.getNameSpace(), Omn_lifecycle.hasState.getLocalName());
            property.addProperty(RDF.type, OWL.FunctionalProperty);
            requestedResource.addProperty(property,Omn_lifecycle.Uncompleted);


        }


        try {
            TripletStoreAccessor.addModel(model);


        } catch (TripletStoreAccessor.ResourceRepositoryException e) {
            LOGGER.log(Level.SEVERE, e.getMessage());
        } catch (InvalidModelException e) {
            LOGGER.log(Level.SEVERE, e.getMessage());
        }
    }


    private static Date getDefaultExpirationTime() {
        Date date = new Date();
        long t = date.getTime();
        return new Date(t + (120 * 60000));
    }

}
