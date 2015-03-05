package marytts.voice.${voice.nameCamelCase};

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.IOException;
import java.util.Locale;

import javax.sound.sampled.AudioInputStream;
import javax.xml.parsers.ParserConfigurationException;

import marytts.LocalMaryInterface;
import marytts.MaryInterface;
import marytts.datatypes.MaryDataType;
import marytts.modules.synthesis.Voice;
import marytts.${voice.type == 'hsmm' ? 'htsengine.HMMVoice' : 'unitselection.UnitSelectionVoice'};
import marytts.util.MaryRuntimeUtils;
import marytts.util.dom.DomUtils;

import org.junit.BeforeClass;
import org.junit.Test;

import org.w3c.dom.Document;
import org.xml.sax.SAXException;

public class LoadVoiceIT {

	@BeforeClass
	public static void beforeClass() throws Exception {
		MaryRuntimeUtils.ensureMaryStarted();
	}

	@Test
	public void canLoadVoice() throws Exception {
		Config config = new Config();
		Voice voice = new ${voice.type == 'hsmm' ? 'HMM' : 'UnitSelection'}Voice(config.getName(), null);
		assertNotNull(voice);
	}

	@Test
	public void canSetVoice() throws Exception {
		MaryInterface mary = new LocalMaryInterface();
		String voiceName = new Config().getName();
		mary.setVoice(voiceName);
		assertEquals(voiceName, mary.getVoice());
	}

	@Test
	public void canProcessTextToSpeech() throws Exception {
		MaryInterface mary = new LocalMaryInterface();
		mary.setVoice(new Config().getName());
		Locale locale = new Locale("$voice.locale.language", "$voice.locale.country");
		String example = MaryDataType.getExampleText(MaryDataType.TEXT, locale);
		AudioInputStream audio = mary.generateAudio(example);
		assertNotNull(audio);
	}

	@Test
	public void canProcessToTargetfeatures() throws Exception {
		MaryInterface mary = new LocalMaryInterface();
        Locale locale = new Locale("$voice.locale.language", "$voice.locale.country");
		mary.setLocale(locale);
		mary.setOutputType(MaryDataType.TARGETFEATURES.name());
		String example = MaryDataType.getExampleText(MaryDataType.TEXT, locale);
		String out = mary.generateText(example);
		assertNotNull(out);
	}

	@Test${voice.maryLocale in ['fr'] ? '(expected = java.lang.AssertionError.class)' : ''}
	public void canProcessTokensToTargetfeatures() throws Exception {
		MaryInterface mary = new LocalMaryInterface();
        Locale locale = new Locale("$voice.locale.language", "$voice.locale.country");
		mary.setLocale(locale);
		mary.setInputType(MaryDataType.TOKENS.name());
		mary.setOutputType(MaryDataType.TARGETFEATURES.name());
		Document doc = getExampleTokens(mary.getLocale());
		String out = mary.generateText(doc);
		assertNotNull(out);
	}

	@Test${voice.maryLocale in ['fr'] ? '(expected = java.lang.AssertionError.class)' : ''}
	public void canProcessTokensToSpeech() throws Exception {
		MaryInterface mary = new LocalMaryInterface();
        Locale locale = new Locale("$voice.locale.language", "$voice.locale.country");
		mary.setLocale(locale);
		mary.setInputType(MaryDataType.TOKENS.name());
		Document doc = getExampleTokens(mary.getLocale());
		AudioInputStream audio = mary.generateAudio(doc);
		assertNotNull(audio);
	}

	private Document getExampleTokens(Locale locale) throws ParserConfigurationException, SAXException, IOException {
		String example = MaryDataType.getExampleText(MaryDataType.TOKENS, locale);
		assertNotNull(example);
		Document doc = DomUtils.parseDocument(example);
		return doc;
	}

}
