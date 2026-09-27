package com.sprintmodus.workitem_service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.sprintmodus.workitem_service.domain.service.MentionParser;
import com.sprintmodus.workitem_service.domain.service.MentionParser.Mention;

class MentionParserTest {

	private static final UUID ANA = UUID.fromString("11111111-1111-4111-8111-111111111111");

	private static final UUID BOB = UUID.fromString("22222222-2222-4222-8222-222222222222");

	private static String token(String name, UUID code) {
		return "@[" + name + "](" + code + ")";
	}

	@Test
	void findsATokenAndItsUserCode() {
		assertThat(MentionParser.parse("Please review, " + token("Ana Diaz", ANA) + "!")).containsExactly(new Mention(ANA, "Ana Diaz"));
	}

	@Test
	void findsSeveralUsersInOrderOfFirstAppearance() {
		assertThat(MentionParser.parse(token("Bob", BOB) + " and " + token("Ana", ANA))).extracting(Mention::userCode).containsExactly(BOB, ANA);
	}

	@Test
	void theSameUserTwiceCountsOnceEvenUnderAnotherNameOrCase() {
		String text = token("Ana", ANA) + " " + token("Ana Diaz", ANA) + " " + token("A", UUID.fromString(ANA.toString().toUpperCase()));

		assertThat(MentionParser.parse(text)).containsExactly(new Mention(ANA, "Ana"));
	}

	@Test
	void anythingThatIsNotExactlyATokenIsPlainText() {
		List<String> notTokens = List.of("@Ana", "@[Ana]", "@[Ana](not-a-uuid)", "@[Ana](" + ANA, "[Ana](" + ANA + ")", "@[](" + ANA + ")",
				"@[Ana Diaz\n](" + ANA + ")", "@[An[a](" + ANA + ")", "@[Ana] (" + ANA + ")", "@[Ana](" + ANA.toString().replace("-", "") + ")",
				"@[" + "x".repeat(256) + "](" + ANA + ")");

		for (String text : notTokens) {
			assertThat(MentionParser.parse(text)).as(text).isEmpty();
			assertThat(MentionParser.plainText(text)).as(text).isEqualTo(text);
		}
	}

	@Test
	void aNameOf255CharactersIsStillAToken() {
		assertThat(MentionParser.parse(token("x".repeat(255), ANA))).hasSize(1);
	}

	@Test
	void plainTextReducesEveryTokenToTheNameAFriendlyReaderSees() {
		assertThat(MentionParser.plainText("Hi " + token("Ana Diaz", ANA) + ", cc " + token("Bob", BOB) + " and @carol")).isEqualTo("Hi @Ana Diaz, cc @Bob and @carol");
	}

	@Test
	void plainTextKeepsCharactersThatMeanSomethingToARegexReplacement() {
		assertThat(MentionParser.plainText(token("Ana $1 \\ Diaz", ANA))).isEqualTo("@Ana $1 \\ Diaz");
	}

	@Test
	void textWithoutTokensHasNoMentions() {
		assertThat(MentionParser.parse("")).isEmpty();
		assertThat(MentionParser.parse("Nothing to see, mail me at a@b.c")).isEmpty();
	}

}
