/*
 * Copyright © 2014-2016 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.data2.datafabric.dataset;

import co.cask.cdap.api.dataset.Dataset;
import co.cask.cdap.api.dataset.DatasetAdmin;
import co.cask.cdap.api.dataset.DatasetContext;
import co.cask.cdap.api.dataset.DatasetManagementException;
import co.cask.cdap.api.dataset.DatasetProperties;
import co.cask.cdap.api.dataset.DatasetSpecification;
import co.cask.cdap.api.dataset.module.DatasetDefinitionRegistry;
import co.cask.cdap.api.dataset.module.DatasetModule;
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.io.Locations;
import co.cask.cdap.common.lang.ClassLoaders;
import co.cask.cdap.data2.datafabric.dataset.type.ConstantClassLoaderProvider;
import co.cask.cdap.data2.datafabric.dataset.type.DatasetClassLoaderProvider;
import co.cask.cdap.data2.dataset2.DatasetDefinitionRegistries;
import co.cask.cdap.data2.dataset2.DatasetDefinitionRegistryFactory;
import co.cask.cdap.data2.dataset2.DatasetFramework;
import co.cask.cdap.data2.dataset2.module.lib.DatasetModules;
import co.cask.cdap.data2.metadata.lineage.AccessType;
import co.cask.cdap.proto.DatasetMeta;
import co.cask.cdap.proto.DatasetModuleMeta;
import co.cask.cdap.proto.DatasetSpecificationSummary;
import co.cask.cdap.proto.DatasetTypeMeta;
import co.cask.cdap.proto.Id;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.security.spi.authentication.AuthenticationContext;
import com.google.common.base.Objects;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;
import com.google.inject.Inject;
import org.apache.twill.discovery.DiscoveryServiceClient;
import org.apache.twill.filesystem.Location;
import org.apache.twill.internal.ApplicationBundler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import javax.annotation.Nullable;

/**
 * {@link co.cask.cdap.data2.dataset2.DatasetFramework} implementation that talks to DatasetFramework Service
 */
@SuppressWarnings("unchecked")
public class RemoteDatasetFramework implements DatasetFramework {
  private static final Logger LOG = LoggerFactory.getLogger(RemoteDatasetFramework.class);

  private final CConfiguration cConf;
  private final LoadingCache<Id.Namespace, DatasetServiceClient> clientCache;
  private final DatasetDefinitionRegistryFactory registryFactory;

  @Inject
  public RemoteDatasetFramework(final CConfiguration cConf, final DiscoveryServiceClient discoveryClient,
                                DatasetDefinitionRegistryFactory registryFactory,
                                final AuthenticationContext authenticationContext) {
    this.cConf = cConf;
    this.clientCache = CacheBuilder.newBuilder().build(new CacheLoader<Id.Namespace, DatasetServiceClient>() {
      @Override
      public DatasetServiceClient load(Id.Namespace namespace) throws Exception {
        return new DatasetServiceClient(discoveryClient, namespace.toEntityId(), cConf, authenticationContext);
      }
    });
    this.registryFactory = registryFactory;
  }

  @Override
  public void addModule(Id.DatasetModule moduleId, DatasetModule module) throws DatasetManagementException {
    Class<?> moduleClass = DatasetModules.getDatasetModuleClass(module);
    try {
      Location deploymentJar = createDeploymentJar(moduleClass);
      try {
        clientCache.getUnchecked(moduleId.getNamespace())
          .addModule(moduleId.getId(), moduleClass.getName(), deploymentJar);
      } finally {
        try {
          deploymentJar.delete();
        } catch (IOException e) {
          // Just log warning, since the add module operation can still proceed
          LOG.warn("Failed to delete temporary deployment jar {}", deploymentJar, e);
        }
      }
    } catch (IOException e) {
      String msg = String.format("Could not create jar for deploying dataset module %s with main class %s",
                                 moduleId, moduleClass.getName());
      LOG.error(msg, e);
      throw new DatasetManagementException(msg, e);
    }
  }

  @Override
  public void addModule(Id.DatasetModule moduleId, DatasetModule module,
                        Location jarLocation) throws DatasetManagementException {
    clientCache.getUnchecked(moduleId.getNamespace())
      .addModule(moduleId.getId(), DatasetModules.getDatasetModuleClass(module).getName(), jarLocation);
  }

  @Override
  public Collection<DatasetModuleMeta> getModules(NamespaceId namespaceId) throws DatasetManagementException {
    return clientCache.getUnchecked(namespaceId.toId()).getAllModules();
  }

  @Override
  public void deleteModule(Id.DatasetModule moduleId) throws DatasetManagementException {
    clientCache.getUnchecked(moduleId.getNamespace()).deleteModule(moduleId.getId());
  }

  @Override
  public void deleteAllModules(Id.Namespace namespaceId) throws DatasetManagementException {
    clientCache.getUnchecked(namespaceId).deleteModules();
  }

  @Override
  public void addInstance(String datasetType, Id.DatasetInstance datasetInstanceId, DatasetProperties props)
    throws DatasetManagementException {
    clientCache.getUnchecked(datasetInstanceId.getNamespace())
      .addInstance(datasetInstanceId.getId(), datasetType, props);
  }

  @Override
  public void updateInstance(Id.DatasetInstance datasetInstanceId, DatasetProperties props)
    throws DatasetManagementException {
    clientCache.getUnchecked(datasetInstanceId.getNamespace())
      .updateInstance(datasetInstanceId.getId(), props);
  }

  @Override
  public Collection<DatasetSpecificationSummary> getInstances(Id.Namespace namespaceId)
    throws DatasetManagementException {
    return clientCache.getUnchecked(namespaceId).getAllInstances();
  }

  @Nullable
  @Override
  public DatasetSpecification getDatasetSpec(Id.DatasetInstance datasetInstanceId) throws DatasetManagementException {
    DatasetMeta meta = clientCache.getUnchecked(datasetInstanceId.getNamespace())
      .getInstance(datasetInstanceId.getId());
    return meta == null ? null : meta.getSpec();
  }

  @Override
  public boolean hasInstance(Id.DatasetInstance datasetInstanceId) throws DatasetManagementException {
    return clientCache.getUnchecked(datasetInstanceId.getNamespace()).getInstance(datasetInstanceId.getId()) != null;
  }

  @Override
  public boolean hasSystemType(String typeName) throws DatasetManagementException {
    return hasType(Id.DatasetType.from(Id.Namespace.SYSTEM, typeName));
  }

  @Override
  public boolean hasType(Id.DatasetType datasetTypeId) throws DatasetManagementException {
    return clientCache.getUnchecked(datasetTypeId.getNamespace()).getType(datasetTypeId.getTypeName()) != null;
  }

  @Override
  public DatasetTypeMeta getTypeInfo(Id.DatasetType datasetTypeId) throws DatasetManagementException {
    return clientCache.getUnchecked(datasetTypeId.getNamespace()).getType(datasetTypeId.getTypeName());
  }

  @Override
  public void truncateInstance(Id.DatasetInstance datasetInstanceId) throws DatasetManagementException {
    clientCache.getUnchecked(datasetInstanceId.getNamespace()).truncateInstance(datasetInstanceId.getId());
  }

  @Override
  public void deleteInstance(Id.DatasetInstance datasetInstanceId) throws DatasetManagementException {
    clientCache.getUnchecked(datasetInstanceId.getNamespace()).deleteInstance(datasetInstanceId.getId());
  }

  @Override
  public void deleteAllInstances(Id.Namespace namespaceId) throws DatasetManagementException, IOException {
    // delete all one by one
    for (DatasetSpecificationSummary metaSummary : getInstances(namespaceId)) {
      Id.DatasetInstance datasetInstanceId = Id.DatasetInstance.from(namespaceId, metaSummary.getName());
      deleteInstance(datasetInstanceId);
    }
  }

  @Override
  public <T extends DatasetAdmin> T getAdmin(Id.DatasetInstance datasetInstanceId, ClassLoader classLoader)
    throws DatasetManagementException, IOException {
    return getAdmin(datasetInstanceId, classLoader, new ConstantClassLoaderProvider(classLoader));
  }

  @Nullable
  @Override
  public <T extends DatasetAdmin> T getAdmin(Id.DatasetInstance datasetInstanceId,
                                             @Nullable ClassLoader parentClassLoader,
                                             DatasetClassLoaderProvider classLoaderProvider)
    throws DatasetManagementException, IOException {
    DatasetMeta instanceInfo = clientCache.getUnchecked(datasetInstanceId.getNamespace())
      .getInstance(datasetInstanceId.getId());
    if (instanceInfo == null) {
      return null;
    }

    DatasetType type = getType(instanceInfo.getType(), parentClassLoader, classLoaderProvider);
    return (T) type.getAdmin(DatasetContext.from(datasetInstanceId.getNamespaceId()), instanceInfo.getSpec());
  }

  @Override
  public <T extends Dataset> T getDataset(
    Id.DatasetInstance datasetInstanceId, Map<String, String> arguments,
    @Nullable ClassLoader classLoader,
    @Nullable Iterable<? extends Id> owners) throws DatasetManagementException, IOException {

    return getDataset(datasetInstanceId, arguments, classLoader, new ConstantClassLoaderProvider(classLoader), owners);
  }

  @Override
  public <T extends Dataset> T getDataset(
    Id.DatasetInstance datasetInstanceId, Map<String, String> arguments,
    @Nullable ClassLoader classLoader) throws DatasetManagementException, IOException {

    return getDataset(datasetInstanceId, arguments, classLoader, null);
  }

  @Nullable
  @Override
  public <T extends Dataset> T getDataset(
    Id.DatasetInstance datasetInstanceId, @Nullable Map<String, String> arguments,
    @Nullable ClassLoader classLoader,
    DatasetClassLoaderProvider classLoaderProvider,
    @Nullable Iterable<? extends Id> owners) throws DatasetManagementException, IOException {

    return getDataset(datasetInstanceId, arguments, classLoader, classLoaderProvider, owners, AccessType.UNKNOWN);
  }

  @Nullable
  @Override
  public <T extends Dataset> T getDataset(Id.DatasetInstance id, @Nullable Map<String, String> arguments,
                                          @Nullable ClassLoader classLoader,
                                          DatasetClassLoaderProvider classLoaderProvider,
                                          @Nullable Iterable<? extends Id> owners, AccessType accessType)
    throws DatasetManagementException, IOException {

    DatasetMeta datasetMeta = clientCache.getUnchecked(id.getNamespace()).getInstance(id.getId(), owners);
    if (datasetMeta == null) {
      return null;
    }

    DatasetType type = getType(datasetMeta.getType(), classLoader, classLoaderProvider);
    return (T) type.getDataset(DatasetContext.from(id.getNamespaceId()), datasetMeta.getSpec(), arguments);
  }

  @Override
  public void writeLineage(Id.DatasetInstance datasetInstanceId, AccessType accessType) {
    // no-op. The RemoteDatasetFramework doesn't need to do anything. The lineage should be recorded before this point.
  }

  private Location createDeploymentJar(Class<?> clz) throws IOException {
    File tempDir = new File(cConf.get(Constants.CFG_LOCAL_DATA_DIR),
                            cConf.get(Constants.AppFabric.TEMP_DIR)).getAbsoluteFile();
    tempDir.mkdirs();
    File tempFile = File.createTempFile(clz.getName(), ".jar", tempDir);
    try {
      // Create a bundle jar in a temp location
      ClassLoader remembered = ClassLoaders.setContextClassLoader(clz.getClassLoader());
      try {
        ApplicationBundler bundler = new ApplicationBundler(ImmutableList.of("co.cask.cdap.api",
                                                                             "org.apache.hadoop",
                                                                             "org.apache.hbase",
                                                                             "org.apache.hive"));
        bundler.createBundle(Locations.toLocation(tempFile), clz);
      } finally {
        ClassLoaders.setContextClassLoader(remembered);
      }

      // Create the program jar for deployment. It removes the "classes/" prefix as that's the convention taken
      // by the ApplicationBundler inside Twill.
      File destination = File.createTempFile(clz.getName(), ".jar", tempDir);
      try (
        JarOutputStream jarOutput = new JarOutputStream(new FileOutputStream(destination));
        JarInputStream jarInput = new JarInputStream(new FileInputStream(tempFile))
      ) {
        Set<String> seen = Sets.newHashSet();
        JarEntry jarEntry = jarInput.getNextJarEntry();
        while (jarEntry != null) {
          boolean isDir = jarEntry.isDirectory();
          String entryName = jarEntry.getName();
          if (!entryName.equals("classes/")) {
            if (entryName.startsWith("classes/")) {
              jarEntry = new JarEntry(entryName.substring("classes/".length()));
            } else {
              jarEntry = new JarEntry(entryName);
            }
            if (seen.add(jarEntry.getName())) {
              jarOutput.putNextEntry(jarEntry);

              if (!isDir) {
                ByteStreams.copy(jarInput, jarOutput);
              }
            }
          }

          jarEntry = jarInput.getNextJarEntry();
        }

        return Locations.toLocation(destination);
      }
    } finally {
      tempFile.delete();
    }
  }

  // can be used directly if DatasetTypeMeta is known, like in create dataset by dataset ops executor service

  /**
   * Return an instance of the {@link DatasetType} corresponding to given dataset modules. Uses the given
   * classloader as a parent for all dataset modules, and the given classloader provider to get classloaders for
   * each dataset module in given the dataset type meta. Order of dataset modules in the given
   * {@link DatasetTypeMeta} is important. The classloader for the first dataset module is used as the parent of
   * the second dataset module and so on until the last dataset module. The classloader for the last dataset module
   * is then used as the classloader for the returned {@link DatasetType}.
   *
   * @param implementationInfo the dataset type metadata to instantiate the type from
   * @param classLoader the parent classloader to use for dataset modules
   * @param classLoaderProvider the classloader provider to get classloaders for each dataset module
   * @param <T> the type of DatasetType
   * @return an instance of the DatasetType
   */
  public <T extends DatasetType> T getDatasetType(DatasetTypeMeta implementationInfo,
                                                  ClassLoader classLoader,
                                                  DatasetClassLoaderProvider classLoaderProvider) {

    return getType(implementationInfo, classLoader, classLoaderProvider);
  }

  /**
   * Return an instance of the {@link DatasetType} corresponding to given dataset modules. Uses the given
   * classloader as a parent for all dataset modules, and the given classloader provider to get classloaders for
   * each dataset module in given the dataset type meta. Order of dataset modules in the given
   * {@link DatasetTypeMeta} is important. The classloader for the first dataset module is used as the parent of
   * the second dataset module and so on until the last dataset module. The classloader for the last dataset module
   * is then used as the classloader for the returned {@link DatasetType}.
   *
   * @param datasetTypeMeta the dataset type metadata to instantiate the type from
   * @param classLoader the parent classloader to use for dataset modules
   * @param classLoaderProvider the classloader provider to get classloaders for each dataset module
   * @param <T> the type of DatasetType
   * @return an instance of the DatasetType
   */
  private <T extends DatasetType> T getType(DatasetTypeMeta datasetTypeMeta,
                                           @Nullable ClassLoader classLoader,
                                           DatasetClassLoaderProvider classLoaderProvider) {

    if (classLoader == null) {
      classLoader = Objects.firstNonNull(Thread.currentThread().getContextClassLoader(), getClass().getClassLoader());
    }

    DatasetDefinitionRegistry registry = registryFactory.create();
    for (DatasetModuleMeta moduleMeta : datasetTypeMeta.getModules()) {
      // adding dataset module jar to classloader
      try {
        classLoader = classLoaderProvider.get(moduleMeta, classLoader);
      } catch (IOException e) {
        LOG.error("Was not able to init classloader for module {} while trying to load type {}",
                  moduleMeta, datasetTypeMeta, e);
        throw Throwables.propagate(e);
      }

      try {
        DatasetDefinitionRegistries.register(moduleMeta.getClassName(), classLoader, registry);
      } catch (Exception e) {
        LOG.error("Was not able to load dataset module class {} while trying to load type {}",
                  moduleMeta.getClassName(), datasetTypeMeta, e);
        throw Throwables.propagate(e);
      }
    }

    // contract of DatasetTypeMeta is that the last module returned by getModules() is the one
    // that announces the dataset's type. The classloader for the returned DatasetType must be the classloader
    // for that last module.
    return (T) new DatasetType(registry.get(datasetTypeMeta.getName()), classLoader);
  }
}
