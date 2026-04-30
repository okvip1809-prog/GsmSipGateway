const path = require('path');
const {getDefaultConfig} = require('metro-config');

const root = path.resolve(__dirname, '..');

module.exports = (async () => {
  const defaultConfig = await getDefaultConfig(__dirname);
  return {
    ...defaultConfig,
    projectRoot: __dirname,
    watchFolders: [root, __dirname],
    resolver: {
      ...defaultConfig.resolver,
      nodeModulesPaths: [
        path.resolve(root, 'node_modules'),
      ],
    },
    transformer: {
      ...defaultConfig.transformer,
      babelTransformerPath: require.resolve('metro-babel-transformer'),
      getTransformOptions: async () => ({
        transform: {
          experimentalImportSupport: false,
          inlineRequires: true,
        },
      }),
    },
  };
})();
