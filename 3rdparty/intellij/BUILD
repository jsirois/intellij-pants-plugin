import os

_jdk_jars = [
    'sa-jdi.jar',
    'tools.jar'
]

def is_ultimate():
    return os.getenv('IJ_ULTIMATE', 'false') == 'true'

def _find_all_jars(root_dir):
    result = []
    for root, _, files in os.walk(root_dir):
        for file in files:
            if file.endswith('.jar'):
                result.append(os.path.join(root, file))
    return result

def _jar_path_in_lib_folder(base_dir, jar_name):
    lib_path = os.path.join(base_dir, 'lib')
    return _jar_path_in_folder(lib_path, jar_name)

def _jar_path_in_folder(lib_path, jar_name):
    jar_path = os.path.join(lib_path, jar_name)
    return jar_path

def _intellij_sdk_classpath():
    base_dir = os.environ['INTELLIJ_HOME']
    if not os.path.exists(base_dir):
        raise Exception('Directory %s not exists!' % base_dir)

    def plugin_jar(path, name=None):
        file_name = os.path.basename(path)
        jar_name = '{}.{}'.format(name, file_name) if name else file_name
        return jar(
            org='com.intellij.sdk.%s' % ('ultimate' if is_ultimate() else 'community'),
            name=jar_name,
            rev='latest',
            url='file://%s' % path,
        )

    intellij_jars = []

    # lib
    for file in os.listdir(os.path.join(base_dir, 'lib')):
        if file.endswith('.jar'):
            intellij_jars.append(plugin_jar(os.path.join(base_dir, 'lib', file)))

    for plugin_name in os.listdir(os.path.join(base_dir, 'plugins')):
        plugin_root = os.path.join(base_dir, 'plugins', plugin_name)
        for jar_path in _find_all_jars(plugin_root):
            intellij_jars.append(plugin_jar(jar_path, plugin_name))

    external_api_jar_path = os.path.join(os.getcwd(), 'testData', 'external-system-test-api-impl.jar')
    if not os.path.exists(external_api_jar_path):
        raise Exception('File %s not exists!' % external_api_jar_path)

    intellij_jars.append(plugin_jar(external_api_jar_path))

    return intellij_jars

def _jdk_classpath():
    jdk_libs_home = os.environ['JDK_LIBS_HOME']
    return [ _jar_path_in_folder(jdk_libs_home, jdk_jar) for jdk_jar in _jdk_jars ]

def _find_jars_for_plugin(plugin_name, *jar_names):
    base_dir = os.environ['INTELLIJ_PLUGINS_HOME']
    plugin_home = os.path.join(base_dir, plugin_name)
    if not os.path.exists(plugin_home):
        raise Exception('Directory %s not exists!' % plugin_home)
    return [
        _jar_path_in_lib_folder(plugin_home, jar_name) for jar_name in jar_names
    ]


def _intellij_sdk_jar(path):
    return jar(
        org='com.intellij.sdk',
        name=os.path.basename(path),
        rev='latest',
        url='file://%s' % path,
    )

def _intellij_plugin_jar(path):
    return jar(
        org='com.intellij.plugins',
        name=os.path.basename(path),
        rev='latest',
        url='file://%s' % path,
    )

def _jdk_jar(path):
    return jar(
        org='jdk',
        name=os.path.basename(path),
        rev='latest',
        url='file://%s' % path,
    )

jar_library(
    name = 'sdk',
    jars = _intellij_sdk_classpath() + [ _jdk_jar(path) for path in _jdk_classpath() ]
)

jar_library(
    name = 'python-plugin',
    jars = [
        _intellij_plugin_jar(path) for path in _find_jars_for_plugin('python',
            'python.jar' if is_ultimate() else 'python-community.jar'
        )
    ]
)

jar_library(
    name = 'scala-plugin',
    jars = [
        _intellij_plugin_jar(path) for path in _find_jars_for_plugin('Scala',
            'scala-plugin.jar', 'jps/scala-jps-plugin.jar', 'scala-play-2-jps-plugin.jar', 'compiler-settings.jar'
        )
    ]
)