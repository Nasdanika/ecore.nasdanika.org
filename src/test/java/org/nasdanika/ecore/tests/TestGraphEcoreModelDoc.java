package org.nasdanika.ecore.tests;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.codec.binary.Hex;
import org.eclipse.emf.common.util.DiagnosticException;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.ENamedElement;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EOperation;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EParameter;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.junit.jupiter.api.Test;
import org.nasdanika.common.Context;
import org.nasdanika.common.Diagnostic;
import org.nasdanika.common.ExecutionException;
import org.nasdanika.common.NasdanikaException;
import org.nasdanika.common.NullProgressMonitor;
import org.nasdanika.common.ProgressMonitor;
import org.nasdanika.graph.Element;
import org.nasdanika.graph.emf.EObjectNode;
import org.nasdanika.graph.emf.Util;
import org.nasdanika.graph.processor.IntrospectionLevel;
import org.nasdanika.graph.processor.ProcessorInfo;
import org.nasdanika.html.ecore.doc.EcoreDocLoader;
import org.nasdanika.html.ecore.gen.processors.EcoreNodeProcessorFactory;
import org.nasdanika.html.model.app.Action;
import org.nasdanika.html.model.app.Label;
import org.nasdanika.html.model.app.Link;
import org.nasdanika.html.model.app.gen.ActionSiteGenerator;
import org.nasdanika.html.model.app.graph.Registry;
import org.nasdanika.html.model.app.graph.URINodeProcessor;
import org.nasdanika.html.model.app.graph.emf.EObjectReflectiveProcessorFactory;

/**
 * Tests Ecore -> Graph -> Processor -> actions generation
 * @author Pavel
 *
 */
public class TestGraphEcoreModelDoc {
	
	protected Object eKeyToPathSegment(EAttribute keyAttribute, Object keyValue) {
		return keyValue;
	}	
		
	protected String path(EObjectNode source, EObjectNode target, EReference reference, int index) {
		if (reference.getEKeys().isEmpty() && target.getTarget() instanceof ENamedElement && reference.isUnique()) {
			String name = ((ENamedElement) target.getTarget()).getName();
			if (target.getTarget() instanceof EOperation && !((EOperation) target.getTarget()).getEParameters().isEmpty()) {
				StringBuilder signatureBuilder = new StringBuilder(name);
				EOperation eOperation = (EOperation) target.getTarget();
				// Creating a digest of parameter types to make the id shorter.
				try {
					MessageDigest md = MessageDigest.getInstance("SHA-256");
					
					for (EParameter ep: eOperation.getEParameters()) {
						EClassifier type = ep.getEType();
						String typeStr = type.getName() + "@" + type.getEPackage().getNsURI();
						md.update(typeStr.getBytes(StandardCharsets.UTF_8));
					}
					signatureBuilder.append("-").append(Hex.encodeHexString(md.digest()));			
	
					return signatureBuilder.toString();
				} catch (NoSuchAlgorithmException e) {
					throw new NasdanikaException(e);
				}
			}
			
			return name;
		}
		return Util.path(source, target, reference, index, this::eKeyToPathSegment);
	}
	
	
	@Test
	public void testGraphEcoreDoc() throws IOException, DiagnosticException {
		EPackage ecorePackage = EcorePackage.eINSTANCE;
		List<EObjectNode> nodes = Util.load(Collections.singleton(ecorePackage), this::path);
		
		Context context = Context.EMPTY_CONTEXT;
		ProgressMonitor progressMonitor = new NullProgressMonitor(); // new PrintStreamProgressMonitor();
		Consumer<Diagnostic> diagnosticConsumer = d -> d.dump(System.out, 0);
		
		List<Function<URI,Action>> actionProviders = new ArrayList<>();		

		EcoreDocLoader ecoreDocLoader = new EcoreDocLoader(diagnosticConsumer, context, progressMonitor);
		actionProviders.add(ecoreDocLoader::getPrototype);
		
		EcoreNodeProcessorFactory ecoreNodeProcessorFactory = new EcoreNodeProcessorFactory(context, (uri, pm) -> {
			for (Function<URI, Action> ap: actionProviders) {
				Action prototype = ap.apply(uri);
				if (prototype != null) {
					return prototype;
				}
			}
			return null;
		});
		EObjectReflectiveProcessorFactory eObjectReflectiveProcessorFactory = new EObjectReflectiveProcessorFactory(IntrospectionLevel.ACCESSIBLE, ecoreNodeProcessorFactory);
		
		org.nasdanika.html.model.app.graph.Registry<URI> registry = eObjectReflectiveProcessorFactory.createProcessors(nodes, progressMonitor);
		
		List<Throwable> failures = registry
				.getProcessorInfoMap()
				.values()
				.stream()
				.flatMap(pi -> pi.getFailures().stream())
				.collect(Collectors.toList());
		
		if (!failures.isEmpty()) {
			NasdanikaException ne = new NasdanikaException("Theres's been " + failures.size() +  " failures during processor creation: " + failures);
			for (Throwable failure: failures) {
				ne.addSuppressed(failure);
			}
			throw ne;
		}						
		
		URINodeProcessor ecoreProcessor = null;
		Collection<Throwable> resolveFailures = new ArrayList<>();		
		URI baseActionURI = URI.createURI("https://ecore.nasdanika.org/");
		
		for (Entry<Element, ProcessorInfo<Object, Registry<URI>>> re: registry.getProcessorInfoMap().entrySet()) {
			Element element = re.getKey();
			if (element instanceof EObjectNode) {
				EObjectNode eObjNode = (EObjectNode) element;
				EObject target = eObjNode.getTarget();
				if (target == ecorePackage) {
					ProcessorInfo<Object, Registry<URI>> info = re.getValue();
					Object processor = info.getProcessor();
					if (processor instanceof URINodeProcessor) {
						URINodeProcessor uriNodeProcessor = (URINodeProcessor) processor;
						uriNodeProcessor.resolve(baseActionURI, progressMonitor);
						ecoreProcessor = uriNodeProcessor;
					}
				}
			}
		}			
		
		if (!resolveFailures.isEmpty()) {
			NasdanikaException ne = new NasdanikaException("Theres's been " + resolveFailures.size() +  " failures during URI resolution: " + resolveFailures);
			for (Throwable failure: resolveFailures) {
				ne.addSuppressed(failure);
			}
			throw ne;
		}								
		
		ResourceSet actionModelsResourceSet = new ResourceSetImpl();
		actionModelsResourceSet.getResourceFactoryRegistry().getExtensionToFactoryMap().put(Resource.Factory.Registry.DEFAULT_EXTENSION, new XMIResourceFactoryImpl());
		
		File actionModelsDir = new File("target\\action-models\\");
		actionModelsDir.mkdirs();
		
		File output = new File(actionModelsDir, "ecore.xmi");
		Resource actionModelResource = actionModelsResourceSet.createResource(URI.createFileURI(output.getAbsolutePath()));
		Collection<Label> labels = ecoreProcessor.createLabelsSupplier().call(progressMonitor, diagnosticConsumer);
		for (Label label: labels) {
			if (label instanceof Link) {
				Link link = (Link) label;
				String location = link.getLocation();
				if (!org.nasdanika.common.Util.isBlank(location)) {
					URI uri = URI.createURI(location);
					if (!uri.isRelative()) {
						link.setLocation("${base-uri}" + uri.deresolve(baseActionURI, true, true, true).toString());
					}
				}
			}
		}
		
		actionModelResource.getContents().addAll(labels);
		actionModelResource.save(null);
				
		String rootActionResource = "actions.yml";
		URI rootActionURI = URI.createFileURI(new File(rootActionResource).getAbsolutePath());//.appendFragment("/");
		
		String pageTemplateResource = "page-template.yml";
		URI pageTemplateURI = URI.createFileURI(new File(pageTemplateResource).getAbsolutePath());//.appendFragment("/");
		
		String siteMapDomain = "https://architecture.nasdanika.org";
		
		ActionSiteGenerator actionSiteGenerator = new ActionSiteGenerator() {
			
			protected boolean isDeleteOutputPath(String path) {
				return !"CNAME".equals(path);				
			};
		};
		
		Map<String, Collection<String>> errors = actionSiteGenerator.generate(
				rootActionURI, 
				pageTemplateURI, 
				siteMapDomain, 
				new File("docs"),
				new File("target/doc-site-work-dir"),
				true);
				
		int errorCount = 0;
		for (Entry<String, Collection<String>> ee: errors.entrySet()) {
			System.err.println(ee.getKey());
			for (String error: ee.getValue()) {
				System.err.println("\t" + error);
				++errorCount;
			}
		}
		
		System.out.println("There are " + errorCount + " site errors");
		
		if (errors.size() != 0) {
			throw new ExecutionException("There are problems with pages: " + errorCount);
		}		
	}

}
