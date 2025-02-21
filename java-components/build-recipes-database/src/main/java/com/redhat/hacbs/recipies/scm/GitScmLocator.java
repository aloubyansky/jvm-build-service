package com.redhat.hacbs.recipies.scm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.jboss.logging.Logger;

import com.redhat.hacbs.recipies.BuildRecipe;
import com.redhat.hacbs.recipies.GAV;
import com.redhat.hacbs.recipies.location.ArtifactInfoRequest;
import com.redhat.hacbs.recipies.location.RecipeDirectory;
import com.redhat.hacbs.recipies.location.RecipeGroupManager;
import com.redhat.hacbs.recipies.location.RecipeRepositoryManager;
import com.redhat.hacbs.recipies.util.GitCredentials;

public class GitScmLocator implements ScmLocator {

    private static final Logger log = Logger.getLogger(GitScmLocator.class);

    private static final Pattern NUMERIC_PART = Pattern.compile("(\\d+\\.)(\\d+\\.?)+");

    private static final String DEFAULT_RECIPE_REPO_URL = "https://github.com/redhat-appstudio/jvm-build-data";

    public static String getDefaultRecipeRepoUrl() {
        return DEFAULT_RECIPE_REPO_URL;
    }

    public static GitScmLocator getInstance() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private List<String> recipeRepos = List.of(DEFAULT_RECIPE_REPO_URL);
        private boolean cacheRepoTags;
        private String cacheUrl;
        private ScmLocator fallbackScmLocator;

        private Builder() {
        }

        /**
         * A list of build recipe repo URIs
         *
         * @param recipeRepos recipe repo URIs
         * @return this builder instance
         */
        public Builder setRecipeRepos(List<String> recipeRepos) {
            if (recipeRepos != null && !recipeRepos.isEmpty()) {
                this.recipeRepos = recipeRepos;
            }
            return this;
        }

        /**
         * Whether to cache code repository tags between {@link ScmLocator.resolveTagInfo(GAV)} calls
         *
         * @param cacheRepoTags whether to cache code repository tags
         * @return this builder instance
         */
        public Builder setCacheRepoTags(boolean cacheRepoTags) {
            this.cacheRepoTags = cacheRepoTags;
            return this;
        }

        public Builder setFallback(ScmLocator fallbackScmLocator) {
            this.fallbackScmLocator = fallbackScmLocator;
            return this;
        }

        public GitScmLocator build() {
            return new GitScmLocator(this);
        }
    }

    private final List<String> recipeRepos;
    private final boolean cacheRepoTags;
    private final String cacheUrl;
    private final ScmLocator fallbackScmLocator;
    private final Map<String, Map<String, String>> repoTagsToHash;

    private RecipeGroupManager recipeGroupManager;

    private GitScmLocator(Builder builder) {
        this.recipeRepos = builder.recipeRepos;
        this.cacheRepoTags = builder.cacheRepoTags;
        this.cacheUrl = builder.cacheUrl;
        this.fallbackScmLocator = builder.fallbackScmLocator;
        this.repoTagsToHash = cacheRepoTags ? new HashMap<>() : Map.of();
    }

    private RecipeGroupManager getRecipeGroupManager() {
        if (recipeGroupManager == null) {
            //checkout the git recipe database and load the recipes
            final List<RecipeDirectory> managers = new ArrayList<>(recipeRepos.size());
            for (var i : recipeRepos) {
                log.infof("Checking out recipe repo %s", i);
                try {
                    final Path tempDir = Files.createTempDirectory("recipe");
                    managers.add(RecipeRepositoryManager.create(i, "main", Optional.empty(), tempDir));
                } catch (Exception e) {
                    throw new RuntimeException("Failed to checkout " + i, e);
                }
            }
            recipeGroupManager = new RecipeGroupManager(managers);
        }
        return recipeGroupManager;
    }

    public TagInfo resolveTagInfo(GAV toBuild) {

        log.debugf("Looking up %s", toBuild);

        var recipeGroupManager = getRecipeGroupManager();

        //look for SCM info
        var recipes = recipeGroupManager
                .requestArtifactInformation(
                        new ArtifactInfoRequest(Set.of(toBuild), Set.of(BuildRecipe.SCM, BuildRecipe.BUILD)))
                .getRecipes()
                .get(toBuild);
        var deserialized = recipes == null ? null : recipes.get(BuildRecipe.SCM);
        List<RepositoryInfo> repos = List.of();
        List<TagMapping> allMappings = List.of();
        if (recipes != null && deserialized != null) {
            log.debugf("Found %s %s", recipes, deserialized);
            ScmInfo main;
            try {
                main = BuildRecipe.SCM.getHandler().parse(deserialized);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to parse " + deserialized, e);
            }
            if (main.getLegacyRepos() != null) {
                repos = new ArrayList<>(main.getLegacyRepos().size() + 1);
                allMappings = new ArrayList<>();
                repos.add(main);
                allMappings.addAll(main.getTagMapping());
                for (var j : main.getLegacyRepos()) {
                    repos.add(j);
                    allMappings.addAll(j.getTagMapping());
                }
            } else {
                repos = List.of(main);
                allMappings = main.getTagMapping();
            }
        }

        TagInfo fallbackTagInfo = null;
        if (repos.isEmpty()) {
            log.debugf("No information found, attempting to use the pom to determine the location");
            //TODO: do we want to rely on pom discovery long term? Should we just use this to update the database instead?
            if (fallbackScmLocator != null) {
                fallbackTagInfo = fallbackScmLocator.resolveTagInfo(toBuild);
                if (fallbackTagInfo != null) {
                    repos = List.of(fallbackTagInfo.getRepoInfo());
                }
            }
            if (repos.isEmpty()) {
                throw new RuntimeException("Unable to determine SCM repo");
            }
        }

        RuntimeException firstFailure = null;
        for (var parsedInfo : repos) {
            log.debugf("Looking for a tag in %s", parsedInfo.getUri());

            //now look for a tag
            try {
                final Map<String, String> tagsToHash = getTagToHashMap(parsedInfo);
                if (fallbackTagInfo != null && fallbackTagInfo.getTag() != null) {
                    var hash = tagsToHash.get(fallbackTagInfo.getTag());
                    if (hash != null) {
                        return new TagInfo(fallbackTagInfo.getRepoInfo(), fallbackTagInfo.getTag(), hash);
                    }
                }

                String version = toBuild.getVersion();
                String selectedTag = null;
                Set<String> versionExactContains = new HashSet<>();
                Set<String> tagExactContains = new HashSet<>();

                //first try tag mappings
                for (var mapping : allMappings) {
                    log.debugf("Trying tag pattern %s on version %s", mapping.getPattern(), version);
                    Matcher m = Pattern.compile(mapping.getPattern()).matcher(version);
                    if (m.matches()) {
                        log.debugf("Tag pattern %s matches", mapping.getPattern());
                        String match = mapping.getTag();
                        for (int i = 0; i <= m.groupCount(); ++i) {
                            match = match.replaceAll("\\$" + i, m.group(i));
                        }
                        log.debugf("Trying to find tag %s", match);
                        //if the tag was a constant we don't require it to be in the tag set
                        //this allows for explicit refs to be used
                        if (tagsToHash.containsKey(match) || match.equals(mapping.getTag())) {
                            selectedTag = match;
                            break;
                        }
                    }
                }

                if (selectedTag == null) {
                    for (var name : tagsToHash.keySet()) {
                        if (name.equals(version)) {
                            //exact match is always good
                            selectedTag = version;
                            break;
                        } else if (name.contains(version)) {
                            versionExactContains.add(name);
                        } else if (version.contains(name)) {
                            tagExactContains.add(name);
                        }
                    }
                }
                if (selectedTag == null) {
                    //no exact match
                    if (versionExactContains.size() == 1) {
                        //only one contained the full version
                        selectedTag = versionExactContains.iterator().next();
                    } else {
                        for (var i : versionExactContains) {
                            //look for a tag that ends with the version (i.e. no -rc1 or similar)
                            if (i.endsWith(version)) {
                                if (selectedTag == null) {
                                    selectedTag = i;
                                } else {
                                    throw new RuntimeException(
                                            "Could not determine tag for " + version
                                                    + " multiple possible tags were found: "
                                                    + versionExactContains);
                                }
                            }
                        }
                        if (selectedTag == null && tagExactContains.size() == 1) {
                            //this is for cases where the tag is something like 1.2.3 and the version is 1.2.3.Final
                            //we need to be careful though, as e.g. this could also make '1.2' match '1.2.3'
                            //we make sure the numeric part is an exact match
                            var tempTag = tagExactContains.iterator().next();
                            Matcher tm = NUMERIC_PART.matcher(tempTag);
                            Matcher vm = NUMERIC_PART.matcher(version);
                            if (tm.find() && vm.find()) {
                                if (Objects.equals(tm.group(0), vm.group(0))) {
                                    selectedTag = tempTag;
                                }
                            }
                        }
                        if (selectedTag == null) {
                            RuntimeException runtimeException = new RuntimeException(
                                    "Could not determine tag for " + version);
                            runtimeException.setStackTrace(new StackTraceElement[0]);
                            throw runtimeException;
                        }
                        firstFailure = null;
                    }
                }

                if (selectedTag != null) {
                    return new TagInfo(parsedInfo, selectedTag, tagsToHash.get(selectedTag));
                }
            } catch (RuntimeException ex) {
                log.error("Failure to determine tag", ex);
                if (firstFailure == null) {
                    firstFailure = ex;
                } else {
                    firstFailure.addSuppressed(ex);
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }

        return null;
    }

    private Map<String, String> getTagToHashMap(RepositoryInfo repo) {
        Map<String, String> tagsToHash = repoTagsToHash.get(repo.getUri());
        if (tagsToHash == null) {
            tagsToHash = getTagToHashMapFromGit(repo);
            if (cacheRepoTags) {
                repoTagsToHash.put(repo.getUri(), tagsToHash);
            }
        }
        return tagsToHash;
    }

    private static Map<String, String> getTagToHashMapFromGit(RepositoryInfo parsedInfo) {
        Map<String, String> tagsToHash;
        final Collection<Ref> tags;
        try {
            tags = Git.lsRemoteRepository()
                    .setCredentialsProvider(
                            new GitCredentials())
                    .setRemote(parsedInfo.getUri()).setTags(true).setHeads(false).call();
        } catch (GitAPIException e) {
            throw new RuntimeException("Failed to obtain a list of tags from " + parsedInfo.getUri(), e);
        }
        tagsToHash = new HashMap<>(tags.size());
        for (var tag : tags) {
            var name = tag.getName().replace("refs/tags/", "");
            tagsToHash.put(name, tag.getObjectId().name());
        }
        return tagsToHash;
    }
}
