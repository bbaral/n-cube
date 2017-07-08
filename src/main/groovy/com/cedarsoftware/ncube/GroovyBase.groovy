package com.cedarsoftware.ncube

import com.cedarsoftware.util.EncryptionUtilities
import com.cedarsoftware.util.ReflectionUtils
import com.cedarsoftware.util.StringUtilities
import com.cedarsoftware.util.UrlUtilities
import groovy.transform.CompileStatic
import ncube.grv.exp.NCubeGroovyExpression
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.tools.GroovyClass
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.regex.Matcher

import static com.cedarsoftware.ncube.NCubeAppContext.ncubeRuntime
import static com.cedarsoftware.ncube.NCubeConstants.*

/**
 * Base class for Groovy CommandCells.
 *
 * @author John DeRegnaucourt (jdereg@gmail.com)
 *         <br>
 *         Copyright (c) Cedar Software LLC
 *         <br><br>
 *         Licensed under the Apache License, Version 2.0 (the "License")
 *         you may not use this file except in compliance with the License.
 *         You may obtain a copy of the License at
 *         <br><br>
 *         http://www.apache.org/licenses/LICENSE-2.0
 *         <br><br>
 *         Unless required by applicable law or agreed to in writing, software
 *         distributed under the License is distributed on an "AS IS" BASIS,
 *         WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *         See the License for the specific language governing permissions and
 *         limitations under the License.
 */
@CompileStatic
abstract class GroovyBase extends UrlCommandCell
{
    private static final Logger LOG = LoggerFactory.getLogger(GroovyBase.class)
    public static final String CLASS_NAME_FOR_L2_CALC = 'N_null'
    protected transient String L2CacheKey  // in-memory cache of (SHA-1(source) || SHA-1(URL + classpath.urls)) to compiled class
    protected transient String fullClassName  // full name of compiled class
    private volatile transient Class runnableCode = null
    /**
     * This cache is 'per ApplicationID'.  This allows different applications to define the same
     * class (URL to groovy), yet have different source code for that class.
     */
    private static final ConcurrentMap<ApplicationID, ConcurrentMap<String, Class>> L2_CACHE = new ConcurrentHashMap<>()
    private static String generatedSourcesDir

    //  Private constructor only for serialization.
    protected GroovyBase() {}

    GroovyBase(String cmd, String url, boolean cache)
    {
        super(cmd, url, cache)
    }

    void clearClassLoaderCache()
    {
        runnableCode = null
        super.clearClassLoaderCache()
    }

    /**
     * @return Class the compiled Class the code within this cell was compiled to (it could have been found
     * and re-used if the code is the same as another cell).  If the cell is marked cache=true, then this
     * method will return null. This is because the cell retains the cached value of the executed code and
     * therefore the class reference is no longer needed.
     */
    protected Class getRunnableCode()
    {
        return runnableCode
    }

    protected void setRunnableCode(Class runnableCode)
    {
        this.runnableCode = runnableCode
    }

    protected Object fetchResult(Map<String, Object> ctx)
    {
        prepare(cmd ?: url, ctx)
        Object result = executeInternal(ctx)
        return result
    }

    protected abstract String buildGroovy(Map<String, Object> ctx, String className, String theirGroovy)

    protected static void clearCache(ApplicationID appId)
    {
        Map<String, Class> L2Cache = getAppL2Cache(appId)
        L2Cache.clear()
    }

    protected static Map<String, Class> getAppL2Cache(ApplicationID appId)
    {
        ConcurrentMap<String, Class> map = L2_CACHE[appId]

        if (map == null)
        {
            map = new ConcurrentHashMap<>()
            ConcurrentMap mapRef = L2_CACHE.putIfAbsent(appId, map)
            if (mapRef != null)
            {
                map = mapRef
            }
        }
        return map
    }

    protected Object executeInternal(Map<String, Object> ctx)
    {
        try
        {
            Class code = getRunnableCode()
            if (code == null)
            {
                NCube ncube = getNCube(ctx)
                throw new IllegalStateException("Code cleared while cell was executing, n-cube: ${ncube.name}, app: ${ncube.applicationID}")
            }
            final NCubeGroovyExpression exp = (NCubeGroovyExpression) code.newInstance()
            exp.input = getInput(ctx)
            exp.output = getOutput(ctx)
            exp.ncube = getNCube(ctx)
            return invokeRunMethod(exp, ctx)
        }
        catch (InvocationTargetException e)
        {
            throw e.targetException
        }
    }

    /**
     * Fetch constructor (from cache, if cached) and instantiate GroovyExpression
     */
    protected abstract Object invokeRunMethod(NCubeGroovyExpression instance, Map<String, Object> ctx) throws Throwable

    /**
     * Conditionally compile the passed in command.  If it is already compiled, this method
     * immediately returns.  Insta-check because it is just a reference check.
     */
    void prepare(Object data, Map<String, Object> ctx)
    {
        // check L1 cache
        if (getRunnableCode() != null)
        {   // If the code for the cell has already been compiled, do nothing.
            return
        }

        if (!L2CacheKey) {
            computeL2CacheKey(data, ctx)
        }
        Map<String, Class> L2Cache = getAppL2Cache(getNCube(ctx).applicationID)

        // check L2 cache
        if (L2Cache.containsKey(L2CacheKey))
        {   // Already been compiled, re-use class (different cell, but has identical source or URL as other expression).
            setRunnableCode(L2Cache[L2CacheKey])
            return
        }

        // Pre-compiled check (e.g. source code was pre-compiled and instrumented for coverage)
        Map ret = getClassLoaderAndSource(ctx)
        if (ret.gclass instanceof Class)
        {   // Found class matching URL fileName.groovy already in JVM
            setRunnableCode(ret.gclass as Class)
            L2Cache[L2CacheKey] = ret.gclass as Class
            return
        }

        GroovyClassLoader gcLoader = ret.loader as GroovyClassLoader
        String groovySource = ret.source as String
        compilePrep1(gcLoader, groovySource, ctx)
    }

    /**
     * Ensure that the sys.classpath CdnClassLoader is used during compilation.  It has additional
     * classpath entries that the application developers likely have added.
     * @return Class the compile Class associated to the main class (root of source passed in)
     */
    protected Class compilePrep1(GroovyClassLoader gcLoader, String groovySource, Map<String, Object> ctx)
    {
        // Newly encountered source - compile the source and store it in L1, L2, and L3 caches
        ClassLoader originalClassLoader = Thread.currentThread().contextClassLoader
        try
        {
            // Internally, Groovy sometimes uses the Thread.currentThread().contextClassLoader, which is not the
            // correct class loader to use when inside a container.
            Thread.currentThread().contextClassLoader = gcLoader
            return compilePrep2(gcLoader, groovySource, ctx)
        }
        finally
        {
            Thread.currentThread().contextClassLoader = originalClassLoader
        }
    }

    /**
     * Ensure that the the exact same source class is compiled only one at a time.  The second+
     * concurrent attempts will return the answer from the L2 cache.
     * @return Class the compile Class associated to the main class (root of source passed in)
     */
    protected Class compilePrep2(GroovyClassLoader gcLoader, String groovySource, Map<String, Object> ctx)
    {
        Map<String, Class> L2Cache = getAppL2Cache(getNCube(ctx).applicationID)
        synchronized (lock)
        {
            Class clazz = L2Cache[L2CacheKey]
            if (clazz != null)
            {   // Another thread defined and persisted the class while this thread was blocked...
                setRunnableCode(clazz)
                return clazz
            }

            clazz = compile(gcLoader, groovySource, ctx)
            return clazz
        }
    }

    protected Object getLock()
    {
        return L2CacheKey.intern()
    }

    /**
     * Ensure that the sys.classpath CdnClassLoader is used during compilation.  It has additional
     * classpath entries that the application developers likely have added.
     * @return Class the compile Class associated to the main class (root of source passed in)
     */
    protected Class compile(GroovyClassLoader gcLoader, String groovySource, Map<String, Object> ctx)
    {
        CompilerConfiguration compilerConfiguration = new CompilerConfiguration()
        compilerConfiguration.targetBytecode = targetByteCodeVersion
        compilerConfiguration.debug = NCubeCodeGenDebug
        compilerConfiguration.defaultScriptExtension = '.groovy'
        // TODO: Research when this can be safely turned on vs having to be turned off
        //        compilerConfiguration.optimizationOptions = [(CompilerConfiguration.INVOKEDYNAMIC): Boolean.TRUE]

        SourceUnit sourceUnit = new SourceUnit("ncube.grv.exp.N_${L2CacheKey}", groovySource, compilerConfiguration, gcLoader, null)

        CompilationUnit compilationUnit = new CompilationUnit(gcLoader)
        compilationUnit.addSource(sourceUnit)
        compilationUnit.configure(compilerConfiguration)
        compilationUnit.compile(Phases.CLASS_GENERATION)
        Map<String, Class> L2Cache = getAppL2Cache(getNCube(ctx).applicationID)
        Class generatedClass = defineClasses(gcLoader, compilationUnit.classes, L2Cache, groovySource)
        return generatedClass
    }

    protected Class defineClasses(GroovyClassLoader gcLoader, List classes, Map<String, Class> L2Cache, String groovySource)
    {
        String urlClassName = ''
        if (url != null)
        {
            urlClassName = url - '.groovy'
            urlClassName = urlClassName.replace('/', '.')
        }
        int numClasses = classes.size()
        Class root = null
        byte[] mainClassBytes = null

        for (int i = 0; i < numClasses; i++)
        {
            GroovyClass gclass = classes[i] as GroovyClass
            String className = gclass.name
            def dollarPos = className.indexOf('$')
            boolean isRoot = dollarPos == -1

            // Add compiled class to classLoader
            Class clazz = defineClass(gcLoader, url == null ? gclass.name : null, gclass.bytes)
            if (clazz == null)
            {   // error defining class - may have already been defined thru another route
                continue
            }

            // Persist class bytes
            if (className == urlClassName || className == fullClassName || (isRoot && root == null && NCubeGroovyExpression.isAssignableFrom(clazz)))
            {
                // return reference to main class
                root = clazz
                mainClassBytes = gclass.bytes

                if (url==null)
                {
                    dumpGeneratedSource(className,groovySource)
                }
            }
        }

        if (root == null)
        {
            if (StringUtilities.hasContent(url))
            {
                throw new IllegalStateException("Unable to locate main compiled class: ${urlClassName}.  Does it not extend NCubeGroovyExpression?")
            }
            else
            {
                throw new IllegalStateException("Unable to locate main compiled class. Does it not extend NCubeGroovyExpression? Source:\n${groovySource}")
            }
        }

        // Load root (main class)
        gcLoader.loadClass(ReflectionUtils.getClassNameFromByteCode(mainClassBytes), false, true, true)
        setRunnableCode(root)
        L2Cache[L2CacheKey] = root
        return root
    }

    /**
     * Writes generated Groovy source to the directory identified by the NCUBE_PARAM:genSrcDir
     * @param groovySource
     */
    private static void dumpGeneratedSource(String className, String groovySource) {
        String sourcesDir = getGeneratedSourcesDirectory()
        if (!sourcesDir) {
            return
        }

        File sourceFile = null
        try {
            sourceFile = new File("${sourcesDir}/${className.replace('.',File.separator)}.groovy")
            if (ensureDirectoryExists(sourceFile.getParent())) {
                sourceFile.newWriter().withWriter { w -> w << groovySource }
            }
        }
        catch (Exception e) {
            LOG.warn("Failed to write source file with path=${sourceFile.path}",e)
        }
    }

    /**
     * Returns directory to use for writing source files, if configured and valid
     * @return String specifying valid directory or empty string, if not configured or specified directory was not valid
     */
    protected static String getGeneratedSourcesDirectory()
    {
        if (generatedSourcesDir==null)
        {   // default to SystemParams, if not configured
            setGeneratedSourcesDirectory(ncubeRuntime.getSystemParams()[NCUBE_PARAMS_GENERATED_SOURCES_DIR] as String ?: '')
        }

        return generatedSourcesDir
    }

    /**
     * Controls directory used to store generated sources
     *
     * @param sourcesDir String containing directory to log sources to:
     *      null - will attempt to use value configured in SystemParams
     *      empty - will disable logging
     *      valid directory - directory to use for generated sources
     *   NOTE: if directory cannot be validated, generated sources will be disabled
     */
    protected static void setGeneratedSourcesDirectory(String sourcesDir)
    {
        try
        {
            if (sourcesDir==null)
            {
                generatedSourcesDir = null // allows sources to be reloaded
            }
            else
            {
                generatedSourcesDir = ensureDirectoryExists(sourcesDir) ? sourcesDir : ''
            }

            if (generatedSourcesDir)
            {
                LOG.info("Generated sources configured to use path=${generatedSourcesDir}")
            }
        }
        catch (Exception e)
        {
            LOG.warn("Unable to set sources directory to ${sourcesDir}", e)
            generatedSourcesDir = ''
        }
    }

    /**
     * Validates directory existence
     * @param dirPath String path of directory to validate
     * @return true if directory exists; false, otherwise
     * @throws SecurityException if call to mkdirs encounters an issue
     */
    private static boolean ensureDirectoryExists(String dirPath) {
        if (!dirPath) {
            return false
        }

        File dir = new File(dirPath)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        boolean valid = dir.isDirectory()
        if (!valid)
        {
            LOG.warn("Failed to locate or create generated sources directory with path=${dir.path}")
        }
        return valid
    }

    private static Class defineClass(GroovyClassLoader loader, String className, byte [] byteCode)
    {
        // Add compiled class to classLoader
        try
        {
            Class clazz = loader.defineClass(className, byteCode)
            return clazz
        }
        catch (ThreadDeath t)
        {
            throw t
        }
        catch (ClassCircularityError e)
        {
            LOG.warn("Attempting to defineClass() in GroovyBase", e)
            return null
        }
        catch (LinkageError ignored)
        {
            Class clazz = Class.forName(ReflectionUtils.getClassNameFromByteCode(byteCode), false, loader)
            return clazz
        }
        catch (Throwable t)
        {
            if (byteCode != null)
            {
                LOG.warn("Unable to defineClass: ${ReflectionUtils.getClassNameFromByteCode(byteCode)}", t)
            }
            else
            {
                LOG.warn("Unable to defineClass, null byte code", t)
            }
            return null
        }
    }

    protected Map getClassLoaderAndSource(Map<String, Object> ctx)
    {
        NCube cube = getNCube(ctx)
        boolean isUrlUsed = StringUtilities.hasContent(url)
        Map output = [:]

        if (cube.name.toLowerCase().startsWith("sys."))
        {   // No URLs allowed, nor code from sys.classpath when executing these cubes
            output.loader = (GroovyClassLoader)ncubeRuntime.getLocalClassloader(cube.applicationID)
            output.source = cmd
        }
        else if (isUrlUsed)
        {
            GroovyClassLoader gcLoader = getAppIdClassLoader(ctx)
            if (url.endsWith('.groovy'))
            {
                // If a class exists already with the same name as the groovy file (substituting slashes for dots),
                // then attempt to find and return that class without going through the resource location and parsing
                // code. This can be useful, for example, if a build process pre-builds and load coverage enhanced
                // versions of the classes.
                try
                {
                    String className = url - '.groovy'
                    className = className.replace('/', '.')
                    output.gclass = gcLoader.loadClass(className,false,true,true)
                    LOG.trace("Loaded class:${className},url:${url}")
                    return output
                }
                catch (Exception ignored)
                { }
            }

            URL groovySourceUrl = getActualUrl(ctx)
            output.loader = gcLoader
            output.source = StringUtilities.createUtf8String(UrlUtilities.getContentFromUrl(groovySourceUrl, true))
        }
        else
        {   // inline code
            GroovyClassLoader gcLoader = getAppIdClassLoader(ctx)
            try
            {
                output.gclass = gcLoader.loadClass(fullClassName,false,true,true)
                LOG.trace("Loaded inline class:${fullClassName}")
                return output
            }
            catch (LinkageError error)
            {
                LOG.warn("Failed to load inline class:${fullClassName}. Will attempt to compile",error)
            }
            catch (Exception ignored)
            { }

            output.loader = gcLoader
            output.source = cmd
        }
        output.source = expandNCubeShortCuts(buildGroovy(ctx, "N_${L2CacheKey}", output.source as String))
        return output
    }

    /**
     * Compute SHA1 hash for this CommandCell.  The tricky bit here is that the command can be either
     * defined inline or via a URL.  If defined inline, then the command hash is SHA1(command text).  If
     * defined through a URL, then the command hash is SHA1(command URL + GroovyClassLoader URLs.toString).
     * This is done this way so that every time the same URL is encountered, it does not have to be
     * reread.
     */
    private void computeL2CacheKey(Object data, Map<String, Object> ctx)
    {
        String content
        if (url == null)
        {
            content = expandNCubeShortCuts(buildGroovy(ctx, CLASS_NAME_FOR_L2_CALC, (data != null ? data.toString() : "")))
        }
        else
        {   // specified via URL, add classLoader URL strings to URL for SHA-1 source.
            GroovyClassLoader gcLoader = getAppIdClassLoader(ctx)
            URL[] urls = gcLoader.URLs
            StringBuilder s = new StringBuilder()
            for (URL url : urls)
            {
                s.append(url.toString())
                s.append('.')
            }
            s.append(url)
            content = s.toString()
        }
        String cacheKey = EncryptionUtilities.calculateSHA1Hash(StringUtilities.getUTF8Bytes(content))

        if (url==null) {
            String packageName
            String className

            Matcher m = Regexes.hasClassDefPattern.matcher(content)
            if (m.find()) {
                packageName = m.group('packageName')
                className = m.group('className')
            }

            if (className == CLASS_NAME_FOR_L2_CALC || className == null) {
                className = "N_${cacheKey}"
            }
            fullClassName = packageName==null ? className : "${packageName}.${className}"
        }
        L2CacheKey = cacheKey
    }

    private GroovyClassLoader getAppIdClassLoader(Map<String, Object> ctx)
    {
        NCube cube = getNCube(ctx)
        ApplicationID appId = cube.applicationID
        GroovyClassLoader gcLoader = (GroovyClassLoader) ncubeRuntime.getUrlClassLoader(appId, getInput(ctx))
        return gcLoader
    }

    private String getTargetByteCodeVersion()
    {
        return ncubeRuntime.systemParams[NCUBE_PARAMS_BYTE_CODE_VERSION] ?: '1.8'
    }

    private boolean isNCubeCodeGenDebug()
    {
        return 'true'.equalsIgnoreCase(ncubeRuntime.systemParams[NCUBE_PARAMS_BYTE_CODE_DEBUG] as String)
    }

    protected static String expandNCubeShortCuts(String groovy)
    {
        Matcher m = Regexes.groovyAbsRefCubeCellPattern.matcher(groovy)
        String exp = m.replaceAll('$1go($3, \'$2\')')

        m = Regexes.groovyAbsRefCubeCellPatternA.matcher(exp)
        exp = m.replaceAll('$1go($3, \'$2\')')

        m = Regexes.groovyAbsRefCellPattern.matcher(exp)
        exp = m.replaceAll('$1go($2)')

        m = Regexes.groovyAbsRefCellPatternA.matcher(exp)
        exp = m.replaceAll('$1go($2)')

        m = Regexes.groovyRelRefCubeCellPattern.matcher(exp)
        exp = m.replaceAll('$1at($3,\'$2\')')

        m = Regexes.groovyRelRefCubeCellPatternA.matcher(exp)
        exp = m.replaceAll('$1at($3, \'$2\')')

        m = Regexes.groovyRelRefCellPattern.matcher(exp)
        exp = m.replaceAll('$1at($2)')

        m = Regexes.groovyRelRefCellPatternA.matcher(exp)
        exp = m.replaceAll('$1at($2)')
        return exp
    }

    void getCubeNamesFromCommandText(final Set<String> cubeNames)
    {
        getCubeNamesFromText(cubeNames, cmd)
    }

    protected static void getCubeNamesFromText(final Set<String> cubeNames, final String text)
    {
        if (StringUtilities.isEmpty(text))
        {
            return
        }

        Matcher m = Regexes.groovyAbsRefCubeCellPattern.matcher(text)
        while (m.find())
        {
            cubeNames.add(m.group(2))  // based on Regex pattern - if pattern changes, this could change
        }

        m = Regexes.groovyAbsRefCubeCellPatternA.matcher(text)
        while (m.find())
        {
            cubeNames.add(m.group(2))  // based on Regex pattern - if pattern changes, this could change
        }

        m = Regexes.groovyRelRefCubeCellPattern.matcher(text)
        while (m.find())
        {
            cubeNames.add(m.group(2))  // based on Regex pattern - if pattern changes, this could change
        }

        m = Regexes.groovyRelRefCubeCellPatternA.matcher(text)
        while (m.find())
        {
            cubeNames.add(m.group(2))  // based on Regex pattern - if pattern changes, this could change
        }

        m = Regexes.groovyExplicitCubeRefPattern.matcher(text)
        while (m.find())
        {
            cubeNames.add(m.group(2))  // based on Regex pattern - if pattern changes, this could change
        }

        m = Regexes.groovyExplicitJumpPattern.matcher(text)
        while (m.find())
        {
            cubeNames.add(m.group(2))  // based on Regex pattern - if pattern changes, this could change
        }

        m = Regexes.groovyExplicitAtPattern.matcher(text)
        while (m.find())
        {
            cubeNames.add(m.group(2))  // based on Regex pattern - if pattern changes, this could change
        }

        m = Regexes.groovyExplicitGoPattern.matcher(text)
        while (m.find())
        {
            cubeNames.add(m.group(2))  // based on Regex pattern - if pattern changes, this could change
        }
    }

    /**
     * Find all occurrences of 'input.variableName' in the Groovy code
     * and add the variableName as a scope (key).
     * @param scopeKeys Set to add required scope keys to.
     */
    void getScopeKeys(Set<String> scopeKeys)
    {
        Matcher m = Regexes.inputVar.matcher(cmd)
        while (m.find())
        {
            scopeKeys.add(m.group(2))
        }
    }

    static Set<String> extractImportsAndAnnotations(String text, StringBuilder newGroovy)
    {
        Set<String> extractedLines = new LinkedHashSet<>()
        String adjusted = extract(Regexes.importPattern.matcher(text), extractedLines)
        adjusted = extract(Regexes.grapePattern.matcher(adjusted), extractedLines)
        adjusted = extract(Regexes.grabPattern.matcher(adjusted), extractedLines)
        adjusted = extract(Regexes.compileStaticPattern.matcher(adjusted), extractedLines)
        adjusted = extract(Regexes.typeCheckPattern.matcher(adjusted), extractedLines)
        newGroovy.append(adjusted)
        return extractedLines
    }

    static String extract(Matcher matcher, Set<String> extractedLines)
    {
        while (matcher.find())
        {
            extractedLines.add(matcher.group(0))  // based on Regex pattern - if pattern changes, this could change
        }
        matcher.reset()
        return matcher.replaceAll('')
    }
}
