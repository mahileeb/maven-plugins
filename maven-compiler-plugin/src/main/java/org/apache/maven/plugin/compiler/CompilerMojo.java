package org.apache.maven.plugin.compiler;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.compiler.module.JavaModuleDescriptor;
import org.apache.maven.plugin.compiler.module.ModuleInfoParser;
import org.apache.maven.plugin.compiler.module.ProjectAnalyzer;
import org.apache.maven.plugin.compiler.module.ProjectAnalyzerRequest;
import org.apache.maven.plugin.compiler.module.ProjectAnalyzerResult;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.utils.StringUtils;
import org.apache.maven.shared.utils.logging.MessageUtils;
import org.codehaus.plexus.compiler.util.scan.SimpleSourceInclusionScanner;
import org.codehaus.plexus.compiler.util.scan.SourceInclusionScanner;
import org.codehaus.plexus.compiler.util.scan.StaleSourceScanner;

/**
 * Compiles application sources
 *
 * @author <a href="mailto:jason@maven.org">Jason van Zyl </a>
 * @version $Id$
 * @since 2.0
 */
@Mojo( name = "compile", defaultPhase = LifecyclePhase.COMPILE, threadSafe = true, 
    requiresDependencyResolution = ResolutionScope.COMPILE )
public class CompilerMojo
    extends AbstractCompilerMojo
{
    /**
     * The source directories containing the sources to be compiled.
     */
    @Parameter( defaultValue = "${project.compileSourceRoots}", readonly = true, required = true )
    private List<String> compileSourceRoots;

    /**
     * The directory for compiled classes.
     */
    @Parameter( defaultValue = "${project.build.outputDirectory}", required = true, readonly = true )
    private File outputDirectory;

    /**
     * Projects main artifact.
     *
     * @todo this is an export variable, really
     */
    @Parameter( defaultValue = "${project.artifact}", readonly = true, required = true )
    private Artifact projectArtifact;

    /**
     * A list of inclusion filters for the compiler.
     */
    @Parameter
    private Set<String> includes = new HashSet<String>();

    /**
     * A list of exclusion filters for the compiler.
     */
    @Parameter
    private Set<String> excludes = new HashSet<String>();

    /**
     * <p>
     * Specify where to place generated source files created by annotation processing. Only applies to JDK 1.6+
     * </p>
     *
     * @since 2.2
     */
    @Parameter( defaultValue = "${project.build.directory}/generated-sources/annotations" )
    private File generatedSourcesDirectory;

    /**
     * Set this to 'true' to bypass compilation of main sources. Its use is NOT RECOMMENDED, but quite convenient on
     * occasion.
     */
    @Parameter( property = "maven.main.skip" )
    private boolean skipMain;

    @Parameter( defaultValue = "${project.compileClasspathElements}", readonly = true, required = true )
    private List<String> compilePath;
    
    private final boolean allowPartialRequirements = false;

    @Component( hint = "qdox" )
    private ModuleInfoParser moduleInfoParser;

    @Component
    private ProjectAnalyzer projectAnalyzer;

    private List<String> classpathElements;

    private List<String> modulepathElements;

    protected List<String> getCompileSourceRoots()
    {
        return compileSourceRoots;
    }

    protected List<String> getClasspathElements()
    {
        return classpathElements;
    }

    @Override
    protected List<String> getModulepathElements()
    {
        return modulepathElements;
    }

    protected File getOutputDirectory()
    {
        return outputDirectory;
    }

    public void execute()
        throws MojoExecutionException, CompilationFailureException
    {
        if ( skipMain )
        {
            getLog().info( "Not compiling main sources" );
            return;
        }

        super.execute();

        if ( outputDirectory.isDirectory() )
        {
            projectArtifact.setFile( outputDirectory );
        }
    }

    @Override
    protected void preparePaths( Set<File> sourceFiles )
    {
        assert compilePath != null;

        JavaModuleDescriptor moduleDescriptor = null;

        boolean hasModuleDescriptor = false;
        for ( File sourceFile : sourceFiles )
        {
            if ( "module-info.java".equals( sourceFile.getName() ) )
            {
                try
                {
                    moduleDescriptor = moduleInfoParser.getModuleDescriptor( sourceFile.getParentFile() );
                }
                catch ( IOException e )
                {
                    getLog().warn( "Failed to parse module-info.java: " + e.getMessage() );
                }
                hasModuleDescriptor = true;
                break;
            }
        }

        if ( hasModuleDescriptor )
        {
            // For now only allow named modules. Once we can create a graph with ASM we can specify exactly the modules
            // and we can detect if auto modules are used. In that case, MavenProject.setFile() should not be used, so
            // you cannot depend on this project and so it won't be distributed.

            modulepathElements = new ArrayList<String>( compilePath.size() );
            classpathElements = new ArrayList<String>( compilePath.size() );

            ProjectAnalyzerResult analyzerResult;
            try
            {
                Collection<File> dependencyArtifacts = getCompileClasspathElements( getProject() );

                ProjectAnalyzerRequest analyzerRequest = new ProjectAnalyzerRequest()
                                .setBaseModuleDescriptor( moduleDescriptor )
                                .setDependencyArtifacts( dependencyArtifacts );

                analyzerResult = projectAnalyzer.analyze( analyzerRequest );

                if ( !analyzerResult.getRequiredAutomaticModules().isEmpty() )
                {
                    final String message = "Required automodules detected. "
                        + "Please don't publish this project to a public artifact repository!"; 
                    if ( moduleDescriptor.exports().isEmpty() )
                    {
                        // application
                        getLog().info( message );
                    }
                    else
                    {
                        // library
                        writeBoxedWarning( message );
                    }
                }
                
                for ( Map.Entry<File, JavaModuleDescriptor> entry : analyzerResult.getPathElements().entrySet() )
                {
                    if ( !allowPartialRequirements )
                    {
                        modulepathElements.add( entry.getKey().getPath() );
                    }
                    else if ( entry.getValue() == null )
                    {
                        classpathElements.add( entry.getKey().getPath() );
                    }
                    else if ( analyzerResult.getRequiredNormalModules().contains( entry.getValue().name() ) )
                    {
                        modulepathElements.add( entry.getKey().getPath() );
                    }
                    else if ( analyzerResult.getRequiredAutomaticModules().contains( entry.getValue().name() ) )
                    {
                        modulepathElements.add( entry.getKey().getPath() );
                    }
                    else
                    {
                        classpathElements.add( entry.getKey().getPath() );
                    }
                }
            }
            catch ( IOException e )
            {
                getLog().warn( e.getMessage() );
            }

            if ( !classpathElements.isEmpty() )
            {
                if ( compilerArgs == null )
                {
                    compilerArgs = new ArrayList<String>();
                }
                compilerArgs.add( "--add-reads" );
                compilerArgs.add( moduleDescriptor.name() + "=ALL-UNNAMED" );

                if ( !modulepathElements.isEmpty() )
                {
                    compilerArgs.add( "--add-reads" );
                    compilerArgs.add( "ALL-MODULE-PATH=ALL-UNNAMED" );
                }
            }
        }
        else
        {
            classpathElements = compilePath;
            modulepathElements = Collections.emptyList();
        }
    }
    
    private List<File> getCompileClasspathElements( MavenProject project )
    {
        List<File> list = new ArrayList<File>( project.getArtifacts().size() + 1 );

        list.add( new File( project.getBuild().getOutputDirectory() ) );

        for ( Artifact a : project.getArtifacts() )
        {
            list.add( a.getFile() );
        }
        return list;
    }
    
    protected SourceInclusionScanner getSourceInclusionScanner( int staleMillis )
    {
        SourceInclusionScanner scanner;

        if ( includes.isEmpty() && excludes.isEmpty() )
        {
            scanner = new StaleSourceScanner( staleMillis );
        }
        else
        {
            if ( includes.isEmpty() )
            {
                includes.add( "**/*.java" );
            }
            scanner = new StaleSourceScanner( staleMillis, includes, excludes );
        }

        return scanner;
    }

    protected SourceInclusionScanner getSourceInclusionScanner( String inputFileEnding )
    {
        SourceInclusionScanner scanner;

        // it's not defined if we get the ending with or without the dot '.'
        String defaultIncludePattern = "**/*" + ( inputFileEnding.startsWith( "." ) ? "" : "." ) + inputFileEnding;

        if ( includes.isEmpty() && excludes.isEmpty() )
        {
            includes = Collections.singleton( defaultIncludePattern );
            scanner = new SimpleSourceInclusionScanner( includes, Collections.<String>emptySet() );
        }
        else
        {
            if ( includes.isEmpty() )
            {
                includes.add( defaultIncludePattern );
            }
            scanner = new SimpleSourceInclusionScanner( includes, excludes );
        }

        return scanner;
    }

    protected String getSource()
    {
        return source;
    }

    protected String getTarget()
    {
        return target;
    }

    @Override
    protected String getRelease()
    {
        return release;
    }

    protected String getCompilerArgument()
    {
        return compilerArgument;
    }

    protected Map<String, String> getCompilerArguments()
    {
        return compilerArguments;
    }

    protected File getGeneratedSourcesDirectory()
    {
        return generatedSourcesDirectory;
    }

    private void writeBoxedWarning( String message )
    {
        String line = StringUtils.repeat( "*", message.length() + 4 );
        getLog().warn( line );
        getLog().warn( "* " + MessageUtils.buffer().strong( message )  + " *" );
        getLog().warn( line );
    }
}
