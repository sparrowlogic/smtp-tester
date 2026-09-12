package com.sparrowlogic.smtptester.docs;

import com.sparrowlogic.smtptester.config.SmtpTesterProperties;
import org.junit.jupiter.api.Test;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the packaged documentation against the server it describes.
 *
 * <p>Documentation an agent is told to trust is worse than no documentation when it is wrong, and
 * a configuration key that was renamed without the docs following is the way that happens. Rather
 * than asserting on prose, this walks {@link SmtpTesterProperties} and requires every key it can
 * actually bind to appear somewhere in the full reference.
 */
class AgentDocsTest {

    private final AgentDocs docs = new AgentDocs();

    @Test
    void bothDocumentsArePackagedAndNonEmpty() {
        for (final AgentDocs.Document document : AgentDocs.Document.values()) {
            assertThat(this.docs.text(document))
                    .as("%s", document.fileName())
                    .isNotBlank()
                    .startsWith("# smtp-tester");
        }
    }

    @Test
    void theIndexPointsAtTheFullReferenceSoAnAgentCanFindIt() {
        assertThat(this.docs.text(AgentDocs.Document.INDEX)).contains("/llms-full.txt");
    }

    @Test
    void theIndexSaysTheApiIsUnauthenticatedRatherThanLeavingItToBeDiscovered() {
        assertThat(this.docs.text(AgentDocs.Document.INDEX))
                .containsIgnoringCase("no authentication");
    }

    /**
     * The guard that turns a packaging mistake into a startup failure. Without it a build that
     * stopped copying the resources would hand every agent an empty document and say nothing.
     */
    @Test
    void aMissingDocumentFailsLoudlyAndNamesTheResource() {
        assertThatThrownBy(() -> AgentDocs.readResource("/agent-docs/not-packaged.txt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Packaged documentation is missing")
                .hasMessageContaining("/agent-docs/not-packaged.txt");
    }

    @Test
    void everyBindableConfigurationKeyIsDocumented() {
        final String reference = this.docs.text(AgentDocs.Document.FULL);
        final List<String> undocumented = keysOf(SmtpTesterProperties.class, "smtp-tester").stream()
                .filter(key -> !reference.contains(key))
                .toList();
        assertThat(undocumented)
                .as("keys bindable on SmtpTesterProperties but absent from llms-full.txt")
                .isEmpty();
    }

    /** Walks the record tree the way Spring's binder does, producing the dotted, kebab-case keys. */
    private static List<String> keysOf(final Class<?> type, final String prefix) {
        final List<String> keys = new ArrayList<>();
        for (final RecordComponent component : type.getRecordComponents()) {
            final String key = prefix + "." + kebab(component.getName());
            if (component.getType().isRecord()) {
                keys.addAll(keysOf(component.getType(), key));
            } else {
                keys.add(key);
            }
        }
        return keys;
    }

    private static String kebab(final String camel) {
        final StringBuilder out = new StringBuilder();
        for (final char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) {
                out.append('-').append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }
}
