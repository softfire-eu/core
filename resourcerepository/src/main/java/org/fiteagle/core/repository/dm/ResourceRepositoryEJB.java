package org.fiteagle.core.repository.dm;

import java.util.logging.Logger;

import javax.ejb.Remote;
import javax.ejb.Stateless;

import org.fiteagle.api.core.IResourceRepository;
import org.fiteagle.core.repository.ResourceRepository;

@Stateless
@Remote(IResourceRepository.class)
public class ResourceRepositoryEJB implements IResourceRepository {
	private final static Logger LOGGER = Logger
			.getLogger(ResourceRepositoryEJB.class.toString());
	private final ResourceRepository repo;

	public ResourceRepositoryEJB() {
		this.repo = new ResourceRepository();
	}

	public String listResources(final Serialization type) {
		return this.repo.listResources(type);
	}
}