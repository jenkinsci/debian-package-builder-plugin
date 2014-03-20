package hudson.scm;

import hudson.model.AbstractBuild;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;

public class SubversionHack {
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static Map<String, Long> getRevisionsForBuild(SubversionSCM scm, AbstractBuild build) throws IOException, InterruptedException, IllegalArgumentException, IllegalAccessException {
		SCMRevisionState revisionInfo = scm.calcRevisionsFromBuild(build, null, null);
		for(Field field: revisionInfo.getClass().getDeclaredFields()) {
			field.setAccessible(true);
			if (field.getName().equals("revisions")) {
				return (Map<String, Long>) field.get(revisionInfo);
			}
		}
		throw new IllegalArgumentException("Something is broken in reflection or SVN plugin internals changed. So sad.");
	}
}
