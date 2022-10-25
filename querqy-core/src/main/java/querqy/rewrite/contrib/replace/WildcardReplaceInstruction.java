package querqy.rewrite.contrib.replace;

import querqy.CompoundCharSequence;
import querqy.model.logging.ActionLogging;
import querqy.model.logging.MatchLogging;

import java.util.LinkedList;
import java.util.List;

public class WildcardReplaceInstruction extends ReplaceInstruction {

    @FunctionalInterface
    public interface TermCreator {
        CharSequence createTerm(final CharSequence wildcardMatch);
    }

    private final List<TermCreator> termCreators = new LinkedList<>();

    public WildcardReplaceInstruction(final List<? extends CharSequence> replacementTerms) {

        replacementTerms.stream()
                .map(CharSequence::toString)
                .forEach(replacementTerm -> {
                    final int indexWildcardReplacement = replacementTerm.indexOf("$1");
                    if (indexWildcardReplacement < 0) {
                        termCreators.add(0, wildcardMatch -> replacementTerm);

                    } else {

                        final String leftPart = replacementTerm.substring(0, indexWildcardReplacement);
                        final String rightPart = replacementTerm.substring(indexWildcardReplacement + 2);

                        termCreators.add(0, wildcardMatch ->
                                new CompoundCharSequence(null, leftPart, wildcardMatch, rightPart));
                    }
                });
    }

    @Override
    public void apply(final List<CharSequence> seq,
                      final int start,
                      final int exclusiveOffset,
                      final CharSequence wildcardMatch,
                      final List<ActionLogging> actionLoggings) {
        removeTermFromSequence(seq, start, exclusiveOffset, seq, actionLoggings, MatchLogging.MatchType.AFFIX);
        termCreators.forEach(termCreator -> seq.add(start, termCreator.createTerm(wildcardMatch)));
    }
}
