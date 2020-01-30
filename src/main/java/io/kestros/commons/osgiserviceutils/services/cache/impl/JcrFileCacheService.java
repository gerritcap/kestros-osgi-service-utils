package io.kestros.commons.osgiserviceutils.services.cache.impl;

import static io.kestros.commons.osgiserviceutils.utils.OsgiServiceUtils.closeServiceResourceResolver;
import static io.kestros.commons.osgiserviceutils.utils.OsgiServiceUtils.getOpenServiceResourceResolverOrNullAndLogExceptions;
import static io.kestros.commons.osgiserviceutils.utils.ResourceCreationUtils.createTextFileResourceAndCommit;
import static io.kestros.commons.structuredslingmodels.utils.FileModelUtils.adaptToFileType;
import static io.kestros.commons.structuredslingmodels.utils.SlingModelUtils.adaptToBaseResource;
import static io.kestros.commons.structuredslingmodels.utils.SlingModelUtils.getResourceAsBaseResource;
import static org.apache.jackrabbit.JcrConstants.JCR_PRIMARYTYPE;

import io.kestros.commons.osgiserviceutils.exceptions.CacheBuilderException;
import io.kestros.commons.osgiserviceutils.exceptions.CachePurgeException;
import io.kestros.commons.structuredslingmodels.BaseResource;
import io.kestros.commons.structuredslingmodels.exceptions.InvalidResourceTypeException;
import io.kestros.commons.structuredslingmodels.exceptions.ResourceNotFoundException;
import io.kestros.commons.structuredslingmodels.filetypes.BaseFile;
import io.kestros.commons.structuredslingmodels.filetypes.FileType;
import io.kestros.commons.structuredslingmodels.utils.SlingModelUtils;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Deactivate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class JcrFileCacheService extends BaseCacheService {

  private final Logger log = LoggerFactory.getLogger(getClass());

  private ResourceResolver serviceResourceResolver;

  public abstract String getServiceCacheRootPath();

  protected abstract String getServiceUserName();

  protected abstract ResourceResolverFactory getResourceResolverFactory();

  /**
   * Activates Cache service. Opens service ResourceResolver, which is used to build cached files.
   */
  @Activate
  public void activate() {
    serviceResourceResolver = getOpenServiceResourceResolverOrNullAndLogExceptions(
        getServiceUserName(), getServiceResourceResolver(), getResourceResolverFactory(), this);
    try {
      purgeAll(getServiceResourceResolver());
    } catch (final CachePurgeException e) {
      log.error(e.getMessage());
    }
  }

  /**
   * Deactivates the service and closes the associated service ResourceResolver.
   */
  @Deactivate
  public void deactivate() {
    try {
      purgeAll(getServiceResourceResolver());
    } catch (final CachePurgeException e) {
      log.error(e.getMessage());
    }
    closeServiceResourceResolver(getServiceResourceResolver(), this);
  }

  @Nullable
  public ResourceResolver getServiceResourceResolver() {
    return this.serviceResourceResolver;
  }

  protected void createCacheFile(final String content, final String relativePath,
      final FileType type) throws CacheBuilderException {
    final String parentPath = getParentPathFromPath(getServiceCacheRootPath() + relativePath);
    final String newFileName = relativePath.split("/")[relativePath.split("/").length - 1];

    if (getServiceResourceResolver() != null && getServiceResourceResolver().isLive()) {
      if (getServiceResourceResolver().getResource(parentPath) == null) {
        try {
          createResourcesFromPath(parentPath, getServiceResourceResolver());
        } catch (final ResourceNotFoundException | PersistenceException exception) {
          throw new CacheBuilderException(String.format(
              "%s was unable to create jcr file cache for '%s'. Cache root resource not found. %s",
              getClass().getSimpleName(), relativePath, exception.getMessage()));
        }
      }
      try {
        final BaseResource parentResource = getResourceAsBaseResource(parentPath,
            getServiceResourceResolver());
        createTextFileResourceAndCommit(content, type.getOutputContentType(),
            parentResource.getResource(), newFileName, getServiceResourceResolver());
      } catch (final ResourceNotFoundException | PersistenceException exception) {
        throw new CacheBuilderException(
            String.format("%s failed to create jcr cache file for '%s'. %s",
                getClass().getSimpleName(), relativePath, exception.getMessage()));
      }
    } else {
      throw new CacheBuilderException(String.format(
          "%s failed to create jcr cache file for %s due to closed service resourceResolver.",
          getClass().getSimpleName(), relativePath));
    }
  }

  protected <T extends BaseFile> T getCachedFile(final String path, final Class<T> type)
      throws ResourceNotFoundException, InvalidResourceTypeException {
    if (getServiceResourceResolver() != null) {
      final BaseResource cachedFileResource = getResourceAsBaseResource(
          getServiceCacheRootPath() + path, getServiceResourceResolver());
      return adaptToFileType(cachedFileResource, type);
    }
    throw new ResourceNotFoundException("No service resolver to retrieve cached file.");
  }

  protected boolean isFileCached(final String relativePath) {
    if (getServiceResourceResolver() != null) {
      return getServiceResourceResolver().getResource(getServiceCacheRootPath() + relativePath)
             != null;
    }
    return false;
  }

  @Override
  protected void doPurge(final ResourceResolver resourceResolver) throws CachePurgeException {
    log.info("{} purging cache.", getClass().getSimpleName());
    final Resource serviceCacheRootResource = resourceResolver.getResource(
        getServiceCacheRootPath());
    if (serviceCacheRootResource != null) {

      for (final BaseResource cacheRootChild : SlingModelUtils.getChildrenAsBaseResource(
          serviceCacheRootResource)) {
        try {
          resourceResolver.delete(cacheRootChild.getResource());
        } catch (final PersistenceException exception) {
          log.debug("Unable to delete {} while purging cache.", cacheRootChild.getPath());
        }
      }
      log.info("{} successfully purged cache.", getClass().getSimpleName());
    } else {
      throw new CachePurgeException(
          "Failed to purge cache " + getClass().getSimpleName() + ". Cache root resource "
          + getServiceCacheRootPath() + " not found.");
    }
  }

  String getParentPathFromPath(final String path) {
    return path.substring(0, path.lastIndexOf('/'));
  }

  void createResourcesFromPath(String path, final ResourceResolver resolver)
      throws ResourceNotFoundException, PersistenceException {
    if (path.startsWith(getServiceCacheRootPath())) {
      path = path.split(getServiceCacheRootPath())[1];
    }
    final String[] pathSegments = path.split("/");
    final Map<String, Object> properties = new HashMap<>();
    properties.put(JCR_PRIMARYTYPE, "sling:Folder");
    BaseResource parentResource = getResourceAsBaseResource(getServiceCacheRootPath(), resolver);
    for (final String resourceName : pathSegments) {
      final Resource resourceToCreate = resolver.getResource(
          parentResource.getPath() + "/" + resourceName);
      if (resourceToCreate == null) {
        final Resource newResource = resolver.create(parentResource.getResource(), resourceName,
            properties);
        parentResource = adaptToBaseResource(newResource);
      } else {
        parentResource = adaptToBaseResource(resourceToCreate);
      }
    }
  }


}