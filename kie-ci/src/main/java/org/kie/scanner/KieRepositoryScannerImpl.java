package org.kie.scanner;

import org.drools.compiler.kie.builder.impl.InternalKieContainer;
import org.drools.compiler.kproject.ReleaseIdImpl;
import org.drools.compiler.kproject.models.KieModuleModelImpl;
import org.kie.api.builder.ReleaseId;
import org.kie.api.builder.KieModule;
import org.kie.api.builder.KieScanner;
import org.kie.api.builder.Message;
import org.drools.compiler.kie.builder.impl.InternalKieModule;
import org.drools.compiler.kie.builder.impl.InternalKieScanner;
import org.drools.compiler.kie.builder.impl.MemoryKieModule;
import org.drools.compiler.kie.builder.impl.ResultsImpl;
import org.drools.compiler.kie.builder.impl.ZipKieModule;
import org.kie.api.builder.model.KieModuleModel;
import org.kie.api.runtime.KieContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.aether.artifact.Artifact;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.drools.compiler.kie.builder.impl.KieBuilderImpl.setDefaultsforEmptyKieModule;
import static org.kie.scanner.ArtifactResolver.getResolverFor;
import static org.drools.compiler.kie.builder.impl.KieBuilderImpl.buildKieModule;

public class KieRepositoryScannerImpl implements InternalKieScanner {

    private Timer timer;

    private static final Logger log = LoggerFactory.getLogger(KieScanner.class);

    private KieContainer kieContainer;

    private final Set<DependencyDescriptor> usedDependencies = new HashSet<DependencyDescriptor>();

    private ArtifactResolver artifactResolver;

    public void setKieContainer(KieContainer kieContainer) {
        this.kieContainer = kieContainer;
        ReleaseId releaseId = ((InternalKieContainer)kieContainer).getContainerReleaseId();
        if (releaseId == null) {
            throw new RuntimeException("The KieContainer's ReleaseId cannot be null. Are you using a KieClasspathContainer?");
        }
        DependencyDescriptor projectDescr = new DependencyDescriptor(releaseId, kieContainer.getReleaseId().getVersion());
        if (!projectDescr.isFixedVersion()) {
            usedDependencies.add(projectDescr);
        }

        artifactResolver = getResolverFor(kieContainer.getReleaseId(), true);
        indexAtifacts( findKJarAtifacts() );
    }

    private ArtifactResolver getArtifactResolver() {
        if (artifactResolver == null) {
            artifactResolver = new ArtifactResolver();
        }
        return artifactResolver;
    }

    public KieModule loadArtifact(ReleaseId releaseId) {
        return loadArtifact(releaseId, null);
    }

    public KieModule loadArtifact(ReleaseId releaseId, InputStream pomXml) {
        String artifactName = releaseId.toString();
        ArtifactResolver resolver = pomXml != null ? ArtifactResolver.getResolverFor(pomXml) : getArtifactResolver();
        Artifact artifact = resolver.resolveArtifact(artifactName);
        return artifact != null ? buildArtifact(releaseId, artifact, resolver) : loadPomArtifact(releaseId);
    }

    public String getArtifactVersion(ReleaseId releaseId) {
        if (!releaseId.isSnapshot()) {
            return releaseId.getVersion();
        }
        Artifact artifact = getArtifactResolver().resolveArtifact(releaseId.toString());
        return artifact != null ? artifact.getVersion() : null;
    }
    
    private KieModule loadPomArtifact(ReleaseId releaseId) {
        ArtifactResolver resolver = getResolverFor(releaseId, false);
        if (resolver == null) {
            return null;
        }

        MemoryKieModule kieModule = new MemoryKieModule(releaseId);
        addDependencies(kieModule, resolver, resolver.getPomDirectDependencies());
        build(kieModule);
        return kieModule;
    }

    private InternalKieModule buildArtifact(ReleaseId releaseId, Artifact artifact, ArtifactResolver resolver) {
        if (releaseId.isSnapshot()) {
            ((ReleaseIdImpl)releaseId).setSnapshotVersion(artifact.getVersion());
        }
        ZipKieModule kieModule = createZipKieModule(releaseId, artifact.getFile());
        if (kieModule != null) {
            addDependencies(kieModule, resolver, resolver.getArtifactDependecies(new DependencyDescriptor(artifact).toString()));
            build(kieModule);
        }
        return kieModule;
    }
    
    private void addDependencies(InternalKieModule kieModule, ArtifactResolver resolver, List<DependencyDescriptor> dependencies) {
        for (DependencyDescriptor dep : dependencies) {
            Artifact depArtifact = resolver.resolveArtifact(dep.toString());
            if (isKJar(depArtifact.getFile())) {
                ReleaseId depReleaseId = new DependencyDescriptor(depArtifact).getReleaseId();
                ZipKieModule zipKieModule = createZipKieModule(depReleaseId, depArtifact.getFile());
                if (zipKieModule != null) {
                    kieModule.addKieDependency(zipKieModule);
                }
            }
        }
    }

    private static ZipKieModule createZipKieModule(ReleaseId releaseId, File jar) {
        KieModuleModel kieModuleModel = getKieModuleModelFromJar(jar);
        return kieModuleModel != null ? new ZipKieModule(releaseId, kieModuleModel, jar) : null;
    }

    private static KieModuleModel getKieModuleModelFromJar(File jar) {
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile( jar );
            ZipEntry zipEntry = zipFile.getEntry(KieModuleModelImpl.KMODULE_JAR_PATH);
            KieModuleModel kieModuleModel = KieModuleModelImpl.fromXML(zipFile.getInputStream(zipEntry));
            setDefaultsforEmptyKieModule(kieModuleModel);
            return kieModuleModel;
        } catch ( Exception e ) {
        } finally {
			if (zipFile != null) {
				try {
					zipFile.close();
				} catch (IOException e) {
				}
			}
        }
        return null;
    }

    private ResultsImpl build(InternalKieModule kieModule) {
        ResultsImpl messages = new ResultsImpl();
        buildKieModule(kieModule, messages);
        return messages;
    }

    public void start(long pollingInterval) {
        if (pollingInterval <= 0) {
            throw new IllegalArgumentException("pollingInterval must be positive");
        }
        if (timer != null) {
            throw new IllegalStateException("The scanner is already running");
        }
        startScanTask(pollingInterval);
    }

    public void stop() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    private void startScanTask(long pollingInterval) {
        timer = new Timer(true);
        timer.schedule(new ScanTask(), pollingInterval, pollingInterval);
    }

    private class ScanTask extends TimerTask {
        public void run() {
            scanNow();
        }
    }

    public void scanNow() {
        Map<DependencyDescriptor, Artifact> updatedArtifacts = scanForUpdates(usedDependencies);
        if (updatedArtifacts.isEmpty()) {
            return;
        }
        for (Map.Entry<DependencyDescriptor, Artifact> entry : updatedArtifacts.entrySet()) {
            updateKieModule(entry.getKey(), entry.getValue());
        }
        log.info("The following artifacts have been updated: " + updatedArtifacts);
    }

    private void updateKieModule(DependencyDescriptor oldDependency, Artifact artifact) {
        ReleaseId newReleaseId = new DependencyDescriptor(artifact).getReleaseId();
        ZipKieModule kieModule = createZipKieModule(newReleaseId, artifact.getFile());
        if (kieModule != null) {
            ResultsImpl messages = build(kieModule);
            if ( messages.filterMessages(Message.Level.ERROR).isEmpty()) {
                ((InternalKieContainer)kieContainer).updateDependencyToVersion(oldDependency.getArtifactReleaseId(), newReleaseId);
                oldDependency.setArtifactVersion(artifact.getVersion());
            }
        }
    }

    private Map<DependencyDescriptor, Artifact> scanForUpdates(Collection<DependencyDescriptor> dependencies) {
        Map<DependencyDescriptor, Artifact> newArtifacts = new HashMap<DependencyDescriptor, Artifact>();
        for (DependencyDescriptor dependency : dependencies) {
            Artifact newArtifact = getArtifactResolver().resolveArtifact(dependency.toResolvableString());
            if (newArtifact == null) {
                continue;
            }
            DependencyDescriptor resolvedDep = new DependencyDescriptor(newArtifact);
            if (resolvedDep.isNewerThan(dependency)) {
                newArtifacts.put(dependency, newArtifact);
            }
        }
        return newArtifacts;
    }

    private void indexAtifacts(Collection<Artifact> artifacts) {
        for (Artifact artifact : artifacts) {
            usedDependencies.add(new DependencyDescriptor(artifact));
        }
    }

    private Collection<Artifact> findKJarAtifacts() {
        Collection<DependencyDescriptor> deps = getArtifactResolver().getAllDependecies();
        deps = filterNonFixedDependecies(deps);
        Collection<Artifact> artifacts = filterKJarArtifacts( resolveArtifacts(deps) );
        log.info("Artifacts containing a kjar: " + artifacts);
        return artifacts;
    }

    private Collection<DependencyDescriptor> filterNonFixedDependecies(Collection<DependencyDescriptor> dependencies) {
        List<DependencyDescriptor> nonFixedDeps = new ArrayList<DependencyDescriptor>();
        for (DependencyDescriptor dep : dependencies) {
            if (!dep.isFixedVersion()) {
                nonFixedDeps.add(dep);
            }
        }
        return nonFixedDeps;
    }

    private List<Artifact> resolveArtifacts(Collection<DependencyDescriptor> dependencies) {
        List<Artifact> artifacts = new ArrayList<Artifact>();
        for (DependencyDescriptor dep : dependencies) {
            Artifact artifact = getArtifactResolver().resolveArtifact(dep.toString());
            artifacts.add(artifact);
            log.debug( artifact + " resolved to  " + artifact.getFile() );
        }
        return artifacts;
    }

    private Collection<Artifact> filterKJarArtifacts(Collection<Artifact> artifacts) {
        List<Artifact> kJarArtifacts = new ArrayList<Artifact>();
        for (Artifact artifact : artifacts) {
            if (isKJar(artifact.getFile())) {
                kJarArtifacts.add(artifact);
            }
        }
        return kJarArtifacts;
    }

    private boolean isKJar(File jar) {
        ZipFile zipFile;
        try {
            zipFile = new ZipFile( jar );
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        ZipEntry zipEntry = zipFile.getEntry( KieModuleModelImpl.KMODULE_JAR_PATH );
        return zipEntry != null;
    }
}
