package com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild;

import static org.assertj.core.api.Assertions.assertThat;

import com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild.ShellWords.ParsedScript;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ShellWordsTest {

    private ParsedScript parse(String script) {
        return ShellWords.parse(script, Map.of());
    }

    @Test
    void splitsOnSemicolonsAndDoubleAmpersand() {
        ParsedScript parsed = parse("docker build -t x .; docker push x && echo done");

        assertThat(parsed.commands()).containsExactly(
                List.of("docker", "build", "-t", "x", "."),
                List.of("docker", "push", "x"));
    }

    @Test
    void splitsOnNewlines() {
        ParsedScript parsed = parse("docker build -t x .\ndocker push x\n");

        assertThat(parsed.commands()).hasSize(2);
    }

    @Test
    void joinsBackslashNewlineContinuations() {
        ParsedScript parsed = parse("docker build \\\n  -t x \\\n  .");

        assertThat(parsed.commands()).containsExactly(List.of("docker", "build", "-t", "x", "."));
    }

    @Test
    void keepsOnlyTheLeftSideOfAPipe() {
        ParsedScript parsed = parse("docker build -t x . | tee build.log");

        assertThat(parsed.commands()).containsExactly(List.of("docker", "build", "-t", "x", "."));
    }

    @Test
    void skipsCommentsSetAndEcho() {
        ParsedScript parsed = parse("# a comment\nset -e\necho hello\ndocker build -t x .");

        assertThat(parsed.commands()).containsExactly(List.of("docker", "build", "-t", "x", "."));
    }

    @Test
    void tracksSimpleAssignmentsAndExportedOnes() {
        ParsedScript parsed = parse("TAG=v1.0\nexport REGION=us-central1\ndocker build -t x .");

        assertThat(parsed.assignments()).containsEntry("TAG", "v1.0").containsEntry("REGION", "us-central1");
        assertThat(parsed.commands()).containsExactly(List.of("docker", "build", "-t", "x", "."));
    }

    @Test
    void substitutesAKnownVariableInALaterCommand() {
        ParsedScript parsed = parse("TAG=v1.0\ndocker build -t myimage:$TAG .");

        assertThat(parsed.commands()).containsExactly(List.of("docker", "build", "-t", "myimage:v1.0", "."));
    }

    @Test
    void substitutesABracedVariable() {
        ParsedScript parsed = parse("TAG=v1.0\ndocker build -t myimage:${TAG} .");

        assertThat(parsed.commands()).containsExactly(List.of("docker", "build", "-t", "myimage:v1.0", "."));
    }

    /** Cloud Build's own built-ins ($PROJECT_ID) are never in our environment — left as literal, unresolved text. */
    @Test
    void leavesAnUnknownVariableAsALiteralUnresolvedToken() {
        ParsedScript parsed = parse("docker build -t gcr.io/$PROJECT_ID/app .");

        assertThat(parsed.commands()).containsExactly(List.of("docker", "build", "-t", "gcr.io/$PROJECT_ID/app", "."));
    }

    @Test
    void respectsSingleAndDoubleQuotedWordsWithSpaces() {
        ParsedScript parsed = parse("docker run -e MSG='hello world' --label note=\"two words\"");

        assertThat(parsed.commands()).containsExactly(
                List.of("docker", "run", "-e", "MSG=hello world", "--label", "note=two words"));
    }

    @Test
    void handlesEscapedCharactersOutsideQuotes() {
        ParsedScript parsed = parse("echo foo\\ bar");

        // 'echo' is skipped as noise regardless, but this proves the escape didn't break tokenizing
        // (a literal space inside one word, not a word boundary).
        assertThat(parsed.commands()).isEmpty();
        ParsedScript notEcho = parse("touch foo\\ bar.txt");
        assertThat(notEcho.commands()).containsExactly(List.of("touch", "foo bar.txt"));
    }

    @Test
    void anEmptyOrWhitespaceOnlyScriptProducesNoCommands() {
        assertThat(parse("   \n\n  ").commands()).isEmpty();
        assertThat(parse("").commands()).isEmpty();
    }

    @Test
    void seedsFromInitialEnvironment() {
        ParsedScript parsed = ShellWords.parse("docker build -t myimage:$REGION .", Map.of("REGION", "us-east1"));

        assertThat(parsed.commands()).containsExactly(List.of("docker", "build", "-t", "myimage:us-east1", "."));
    }
}
