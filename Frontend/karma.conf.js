// Karma configuration. The Angular defaults, plus a JSON coverage report that
// scripts/coverage-report.mjs reads to account for files no spec loads, and a JSON
// file of every spec's result that ../scripts/test-report.mjs turns into the
// test results page.
const fs = require('fs');
const path = require('path');

function JsonResultsReporter(baseReporterDecorator) {
  baseReporterDecorator(this);
  // Silent: the progress reporter already prints to the console.
  this.adapters = [];
  const results = [];
  this.onSpecComplete = (_browser, r) =>
    results.push({ suite: r.suite, name: r.description, success: r.success, skipped: r.skipped, time: r.time, log: r.log });
  this.onRunComplete = () => {
    const dir = path.join(__dirname, 'test-results');
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, 'karma-results.json'), JSON.stringify({ generatedAt: new Date().toISOString(), results }));
  };
}
JsonResultsReporter.$inject = ['baseReporterDecorator'];

module.exports = function (config) {
  config.set({
    basePath: '',
    frameworks: ['jasmine', '@angular-devkit/build-angular'],
    plugins: [
      require('karma-jasmine'),
      require('karma-chrome-launcher'),
      require('karma-jasmine-html-reporter'),
      require('karma-coverage'),
      require('@angular-devkit/build-angular/plugins/karma'),
      { 'reporter:json-results': ['type', JsonResultsReporter] },
    ],
    client: {
      jasmine: { random: true },
      clearContext: false,
    },
    jasmineHtmlReporter: { suppressAll: true },
    coverageReporter: {
      dir: require('path').join(__dirname, './coverage/trading-app-ui'),
      subdir: '.',
      reporters: [{ type: 'html' }, { type: 'text-summary' }, { type: 'json', file: 'coverage-final.json' }, { type: 'json-summary', file: 'coverage-summary.json' }],
      check: {
        global: { statements: 90, lines: 90, branches: 85, functions: 85 },
      },
    },
    reporters: ['progress', 'kjhtml', 'json-results'],
    browsers: ['Chrome'],
    restartOnFileChange: true,
  });
};
