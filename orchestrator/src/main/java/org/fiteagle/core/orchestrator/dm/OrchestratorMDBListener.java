package org.fiteagle.core.orchestrator.dm;

import com.hp.hpl.jena.rdf.model.*;

import info.openmultinet.ontology.exceptions.InvalidModelException;
import info.openmultinet.ontology.vocabulary.Omn;
import info.openmultinet.ontology.vocabulary.Omn_lifecycle;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ejb.ActivationConfigProperty;
import javax.ejb.MessageDriven;
import javax.inject.Inject;
import javax.jms.JMSContext;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.Topic;

import info.openmultinet.ontology.vocabulary.Omn_service;

import org.fiteagle.api.core.*;
import org.fiteagle.core.orchestrator.RequestHandler;
import org.fiteagle.core.tripletStoreAccessor.TripletStoreAccessor;
import org.fiteagle.core.tripletStoreAccessor.TripletStoreAccessor.ResourceRepositoryException;

import com.hp.hpl.jena.vocabulary.RDF;

@MessageDriven(activationConfig = {
        @ActivationConfigProperty(propertyName = "destinationType", propertyValue = "javax.jms.Topic"),
        @ActivationConfigProperty(propertyName = "destination", propertyValue = IMessageBus.TOPIC_CORE),
        @ActivationConfigProperty(propertyName = "messageSelector", propertyValue = MessageFilters.FILTER_ORCHESTRATOR),
        @ActivationConfigProperty(propertyName = "acknowledgeMode", propertyValue = "Auto-acknowledge")})
public class OrchestratorMDBListener implements MessageListener {

    private static Logger LOGGER = Logger
            .getLogger(OrchestratorMDBListener.class.toString());

    @Inject
    OrchestratorStateKeeper stateKeeper;

    @Inject
    RequestHandler requestHandler;


    @Inject
    private JMSContext context;
    @javax.annotation.Resource(mappedName = IMessageBus.TOPIC_CORE_NAME)
    private Topic topic;

    public void onMessage(final Message message) {
        String messageType = MessageUtil.getMessageType(message);
        String serialization = MessageUtil.getMessageSerialization(message);
        String messageBody = MessageUtil.getStringBody(message);
        LOGGER.log(Level.INFO, "Received a " + messageType + " message");

        if (messageType != null && messageBody != null) {
            if (messageType.equals(IMessageBus.TYPE_CONFIGURE)) {
                Model messageModel = MessageUtil.parseSerializedModel(
                        messageBody, serialization);
                handleConfigureRequest(messageModel, serialization,
                        MessageUtil.getJMSCorrelationID(message));
            } else if (messageType.equals(IMessageBus.TYPE_DELETE)) {
                Model messageModel = MessageUtil.parseSerializedModel(
                        messageBody, serialization);
                handleDeleteRequest(messageModel, serialization,
                        MessageUtil.getJMSCorrelationID(message));
            } else if (messageType.equals(IMessageBus.TYPE_INFORM)) {
                try {
                    handleInform(messageBody,
                            MessageUtil.getJMSCorrelationID(message));
                } catch (ResourceRepositoryException e) {
                    LOGGER.log(Level.WARNING, e.getMessage());
                } catch (InvalidModelException e) {
                    LOGGER.log(Level.WARNING, e.getMessage());
                }
            } else if (messageType.equals(IMessageBus.TYPE_CREATE)) {
                Model messageModel = MessageUtil.parseSerializedModel(
                        messageBody, serialization);
                handleCreateRequest(messageModel, MessageUtil.getJMSCorrelationID(message));
            } else if (messageType.equals(IMessageBus.TYPE_GET)) {
                handleGet(messageBody, MessageUtil.getJMSCorrelationID(message));
            }
        }
    }



    private void handleCreateRequest(Model messageModel, String jmsCorrelationID) {
        LOGGER.log(Level.INFO, "Orchestrator received a create");

        RequestContext requestContext = new RequestContext(jmsCorrelationID);

        requestHandler.parseModel(requestContext, messageModel, IMessageBus.TYPE_CREATE);

        this.createResources(requestContext);


    }

    private void handleInform(String body, String requestID) throws ResourceRepositoryException, InvalidModelException {

        Request request = stateKeeper.getRequest(requestID);
        Model model = null;
        ResIterator resIterator = null;
        RequestContext requestContext = null;
        if (request != null) {

            switch(request.getMethod()){
                case IMessageBus.TYPE_CREATE:

                    LOGGER.log(Level.INFO, "Orchestrator received a reply");
                     model = MessageUtil.parseSerializedModel(body, IMessageBus.SERIALIZATION_TURTLE);
                    TripletStoreAccessor.updateModel(model);
                     resIterator = model.listSubjects();
                    while(resIterator.hasNext()){
                        request.addOrUpdate(resIterator.nextResource());
                    }
                     requestContext = request.getContext();
                    request.setHandled();
                    if (requestContext.allAnswersReceived()) {
                        Model response = ModelFactory.createDefaultModel();
                        for (Request request1 : requestContext.getRequestMap().values()) {

                            for (Resource resource : request1.getResourceList()) {
                                if(resource.hasProperty(Omn.isResourceOf)){
                                    String topologyURI = resource.getProperty(Omn.isResourceOf).getObject().asResource().getURI();
                                    Model top = TripletStoreAccessor.getResource(topologyURI);
                                    response.add(top);
                                    response.add(resource.listProperties());
                                    Model reservationModel = TripletStoreAccessor.getResource(resource.getProperty(Omn.hasReservation).getObject().asResource().getURI());
                                    Resource reservation = reservationModel.getResource(resource.getProperty(Omn.hasReservation).getObject().asResource().getURI());

                                    reservation.removeAll(Omn_lifecycle.hasReservationState);
                                    reservation.addProperty(Omn_lifecycle.hasReservationState, Omn_lifecycle.Provisioned);

                                    response.add(reservationModel);

                                    TripletStoreAccessor.updateModel(reservationModel);

                                    StmtIterator stmtIterator = model.listStatements(new SimpleSelector(resource, Omn.hasService, (Object)null));
                                    while(stmtIterator.hasNext()){
                                        Statement statement = stmtIterator.nextStatement();
                                        response.add(statement);
                                        response.add(TripletStoreAccessor.getResource(statement.getObject().asResource().getURI()));
                                    }
                                }

                                stateKeeper.removeRequest(requestID);
                            }

                        }
                        sendResponse(requestContext.getRequestContextId(), response);


                    }
                    break;
                case IMessageBus.TYPE_CONFIGURE:

                    LOGGER.log(Level.INFO, "Orchestrator received a reply");
                     model = MessageUtil.parseSerializedModel(body, IMessageBus.SERIALIZATION_TURTLE);
                    TripletStoreAccessor.updateModel(model);
                     resIterator = model.listSubjects();
                    while(resIterator.hasNext()){
                        request.addOrUpdate(resIterator.nextResource());
                    }
                     requestContext = request.getContext();
                    request.setHandled();
                    if (requestContext.allAnswersReceived()) {
                        Model response = ModelFactory.createDefaultModel();
                        for (Request request1 : requestContext.getRequestMap().values()) {

                            for (Resource resource : request1.getResourceList()) {
                                if(resource.hasProperty(Omn.isResourceOf)){
                                    String topologyURI = resource.getProperty(Omn.isResourceOf).getObject().asResource().getURI();
                                    Model top = TripletStoreAccessor.getResource(topologyURI);
                                    response.add(top);
                                    response.add(resource.listProperties());
                                    Model reservationModel = TripletStoreAccessor.getResource(resource.getProperty(Omn.hasReservation).getObject().asResource().getURI());
                                    Resource reservation = reservationModel.getResource(resource.getProperty(Omn.hasReservation).getObject().asResource().getURI());

                                    reservation.removeAll(Omn_lifecycle.hasReservationState);
                                    reservation.addProperty(Omn_lifecycle.hasReservationState, Omn_lifecycle.Provisioned);

                                    response.add(reservationModel);

                                    TripletStoreAccessor.updateModel(reservationModel);
                                }

                                stateKeeper.removeRequest(requestID);
                            }

                        }
                        sendResponse(requestContext.getRequestContextId(), response);


                    }
                    break;
                case IMessageBus.TYPE_DELETE:
                    LOGGER.log(Level.INFO, "Received response to delete request");
                    model = MessageUtil.parseSerializedModel(body, IMessageBus.SERIALIZATION_TURTLE);
                    ResIterator subjects = model.listSubjects();
                    while(subjects.hasNext()){
                        Resource subject = subjects.nextResource();
                        Model currentModel = TripletStoreAccessor.getResource(subject.getURI());
                        //hard delete
                        TripletStoreAccessor.deleteModel(currentModel);

                    }
                    requestContext = request.getContext();
                    request.setHandled();
                    if (requestContext.allAnswersReceived()) {
                        Model response = ModelFactory.createDefaultModel();
                        for (Request request1 : requestContext.getRequestMap().values()) {

                            for (Resource resource : request1.getResourceList()) {
                                if (resource.hasProperty(Omn.isResourceOf)) {
                                    String topologyURI = resource.getProperty(Omn.isResourceOf).getObject().asResource().getURI();
                                    Model top = TripletStoreAccessor.getResource(topologyURI);
                                    response.add(top);
                                    response.add(resource.listProperties());
                                    Model reservationModel = TripletStoreAccessor.getResource(resource.getProperty(Omn.hasReservation).getObject().asResource().getURI());
                                    Resource reservation = reservationModel.getResource(resource.getProperty(Omn.hasReservation).getObject().asResource().getURI());

                                    reservation.removeAll(Omn_lifecycle.hasReservationState);
                                    reservation.addProperty(Omn_lifecycle.hasReservationState, Omn_lifecycle.Unallocated);

                                    response.add(reservationModel);

                                    TripletStoreAccessor.updateModel(reservationModel);
                                }

                                stateKeeper.removeRequest(requestID);
                            }

                        }
                        sendResponse(requestContext.getRequestContextId(), response);
                    }

                    break;
                default: LOGGER.log(Level.INFO, "Sing it baby!");

            }

        }
    }

    private void handleConfigureRequest(Model requestModel,
                                        String serialization, String requestID) {
        LOGGER.log(Level.INFO, "handling configure request ...");

        RequestContext requestContext = new RequestContext(requestID);

        requestHandler.parseModel(requestContext, requestModel, IMessageBus.TYPE_CONFIGURE);

        this.configureResources(requestContext);


    }

    private void handleDeleteRequest(Model requestModel, String serialization, String requestID) {
        LOGGER.log(Level.INFO, "handling delete request ...");

        final Model modelDelete = ModelFactory.createDefaultModel();
        ResIterator resIterator = requestModel.listSubjectsWithProperty(RDF.type,Omn.Topology);
        if(resIterator.hasNext()){
            Resource topology = resIterator.nextResource();
            Model storedModel = TripletStoreAccessor.getResource(topology.getURI());
            StmtIterator stmtIterator = storedModel.listStatements(new SimpleSelector(null,Omn.hasResource,(Object) null));
            while(stmtIterator.hasNext()){
                Statement statement = stmtIterator.nextStatement();
                Resource resource = statement.getObject().asResource();
                modelDelete.add(TripletStoreAccessor.getResource(resource.getURI()));
            }

            RequestContext requestContext = new RequestContext(requestID);
            requestHandler.parseModel(requestContext, modelDelete, IMessageBus.TYPE_DELETE);
            this.sendDeleteToResources(requestContext);

        }else{
            RequestContext requestContext = new RequestContext(requestID);
            requestHandler.parseModel(requestContext, requestModel, IMessageBus.TYPE_DELETE);
            this.sendDeleteToResources(requestContext);
        }



    }

    private void sendDeleteToResources(RequestContext requestContext) {
        Map<String, Request> requestMap = requestContext.getRequestMap();

        for (String requestId : requestMap.keySet()) {
            deleteResource(requestMap.get(requestId));
        }
    }

    private void deleteResource(Request request) {
        Model requestModel = ModelFactory.createDefaultModel();
        Resource requestTopology = requestModel.createResource(IConfig.TOPOLOGY_NAMESPACE_VALUE+ UUID.randomUUID());
        requestTopology.addProperty(RDF.type, Omn.Topology);

        for (Resource resource : request.getResourceList()) {
            Model messageModel = TripletStoreAccessor.getResource(resource.getURI());
            Resource storedResource = messageModel.getResource(resource.getURI());
            if(storedResource.getProperty(Omn_lifecycle.hasState).getObject().asResource().equals(Omn_lifecycle.Uncompleted)){

            }
            requestTopology.addProperty(Omn.hasResource,storedResource);
            requestModel.add(resource.listProperties());
        }

        Model targetModel = TripletStoreAccessor.getResource(request.getTarget());

        String target = targetModel.getResource(request.getTarget()).getProperty(RDF.type).getObject().asResource().getURI();

        Message message = MessageUtil.createRDFMessage(requestModel, IMessageBus.TYPE_DELETE, target, IMessageBus.SERIALIZATION_TURTLE, request.getRequestId(), context);

        context.createProducer().send(topic, message);
    }




    private void sendResponse(String requestID, Model responseModel) {

        String serializedResponse = MessageUtil.serializeModel(responseModel,
                IMessageBus.SERIALIZATION_TURTLE);
        System.out.println("response model " + serializedResponse);
        Message responseMessage = MessageUtil.createRDFMessage(
                serializedResponse, IMessageBus.TYPE_INFORM, null,
                IMessageBus.SERIALIZATION_TURTLE, requestID, context);
        LOGGER.log(Level.INFO, " a reply is sent to SFA ...");
        context.createProducer().send(topic, responseMessage);
    }






    private void handleGet(String messageBody, String jmsCorrelationID) {
        Model model = MessageUtil.parseSerializedModel(messageBody, IMessageBus.SERIALIZATION_TURTLE);
        ResIterator resIterator = model.listSubjects();
        Model responseModel = ModelFactory.createDefaultModel();

        while (resIterator.hasNext()) {
            Resource r = resIterator.nextResource();
            Model m = TripletStoreAccessor.getResource(r.getURI());
            if (r.hasProperty(RDF.type, Omn.Resource)) {


                Resource resource = m.getResource(r.getURI());
                getTopologyAndAddToResponse(responseModel,resource);
                getReservationAndAddToResponse(responseModel,resource);
                responseModel.add(m);
            } else if (r.hasProperty(RDF.type, Omn.Topology)) {
                StmtIterator stmtIterator = m.listStatements(new SimpleSelector(r, Omn.hasResource, (Object) null));

                while (stmtIterator.hasNext()) {
                    Statement statement = stmtIterator.nextStatement();
                    String resourceURI = statement.getObject().asResource().getURI();
                    Model resourceModel = TripletStoreAccessor.getResource(resourceURI);
                    Resource resource = resourceModel.getResource(resourceURI);
                    getReservationAndAddToResponse(responseModel,  resource);
                    responseModel.add(resourceModel);

                }

            }


        }
        sendResponse(jmsCorrelationID, responseModel);
    }

    private void getTopologyAndAddToResponse(Model responseModel, Resource resource) {
        Model topology = TripletStoreAccessor.getResource(resource.getProperty(Omn.isResourceOf).getObject().asResource().getURI());
        responseModel.add(topology);
    }

    private void getReservationAndAddToResponse(Model responseModel, Resource resource) {
        Model reservation = TripletStoreAccessor.getResource(resource.getProperty(Omn.hasReservation).getObject().asResource().getURI());


        responseModel.add(reservation);
    }
     private void createResources(RequestContext requestContext) {

        Map<String, Request> requestMap = requestContext.getRequestMap();

        for (String requestId : requestMap.keySet()) {
            sendCreateToResource(requestMap.get(requestId));
        }
    }

    private void configureResources(RequestContext requestContext) {

        Map<String, Request> requestMap = requestContext.getRequestMap();

        for (String requestId : requestMap.keySet()) {
            sendConfigureToResource(requestMap.get(requestId));
        }
    }


    private void sendCreateToResource(Request request) {
        Model requestModel = ModelFactory.createDefaultModel();
        Resource requestTopology = requestModel.createResource(IConfig.TOPOLOGY_NAMESPACE_VALUE+ UUID.randomUUID());
        requestTopology.addProperty(RDF.type, Omn.Topology);

        for (Resource resource : request.getResourceList()) {
            Model messageModel = TripletStoreAccessor.getResource(resource.getURI());
            requestTopology.addProperty(Omn.hasResource, messageModel.getResource(resource.getURI()));
            requestModel.add(resource.listProperties());
        }

        Model targetModel = TripletStoreAccessor.getResource(request.getTarget());
//        Resource resource =  targetModel.getResource(request.getTarget());
//        Resource adapterinstance = resource.getProperty(Omn_lifecycle.implementedBy).getObject().asResource();

        final Resource resourceToBeCreated = targetModel.getResource(request.getTarget());
		LOGGER.log(Level.INFO, "Creating new resource: " + resourceToBeCreated);

		final Statement resourceTypeToBeCreated = resourceToBeCreated.getProperty(RDF.type);
		LOGGER.log(Level.INFO, "Creating new resource of type: " + resourceTypeToBeCreated);

		if (null == resourceTypeToBeCreated) {
			//@todo: send a proper error message, since "operation timeout is not useful"
			final String errorText = "The type of the requested resource '" + resourceToBeCreated + "' is null!";
            //Message errorMessage = MessageUtil.createErrorMessage(errorText, request.getContext().getRequestContextId(), context);
            //context.createProducer().send(topic, errorMessage);
			throw new RuntimeException(errorText);
		}

        String target = resourceTypeToBeCreated.getObject().asResource().getURI();

        Message message = MessageUtil.createRDFMessage(requestModel, IMessageBus.TYPE_CREATE, target, IMessageBus.SERIALIZATION_TURTLE, request.getRequestId(), context);

        context.createProducer().send(topic, message);

    }
    private void sendConfigureToResource(Request request) {

        Model requestModel = ModelFactory.createDefaultModel();
        Resource requestTopology = requestModel.createResource(IConfig.TOPOLOGY_NAMESPACE_VALUE+ UUID.randomUUID());
        requestTopology.addProperty(RDF.type, Omn.Topology);

        for (Resource resource : request.getResourceList()) {
            Model messageModel = TripletStoreAccessor.getResource(resource.getURI());
            requestTopology.addProperty(Omn.hasResource, messageModel.getResource(resource.getURI()));
            requestModel.add(resource.listProperties());
        }

        Model targetModel = TripletStoreAccessor.getResource(request.getTarget());
//        Resource resource =  targetModel.getResource(request.getTarget());
//        Resource adapterinstance = resource.getProperty(Omn_lifecycle.implementedBy).getObject().asResource();


        String target = targetModel.getResource(request.getTarget()).getProperty(RDF.type).getObject().asResource().getURI();

        Message message = MessageUtil.createRDFMessage(requestModel, IMessageBus.TYPE_CONFIGURE, target, IMessageBus.SERIALIZATION_TURTLE, request.getRequestId(), context);

        context.createProducer().send(topic, message);

    }
}
