package com.github.rmannibucau.jaxrsanalyzer.backend;

import static java.util.Optional.ofNullable;

import com.sebastian_daschner.jaxrs_analyzer.backend.asciidoc.AsciiDocBackend;
import com.sebastian_daschner.jaxrs_analyzer.model.rest.ResourceMethod;

public class EnrichedAsciidocBackend extends AsciiDocBackend {

    @Override
    protected void appendMethod(final String baseUri, final String resource, final ResourceMethod resourceMethod) {
        super.appendMethod(baseUri, resource, resourceMethod);
        ofNullable(resourceMethod.getDescription()).ifPresent(d -> builder.append(d).append("\n\n"));
    }

    @Override
    public String getName() {
        return "asciidoc"; // until https://github.com/sdaschner/jaxrs-analyzer-maven-plugin/issues/50 is fixed
    }
}
