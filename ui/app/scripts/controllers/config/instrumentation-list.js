/*
 * Copyright 2012-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/* global glowroot, angular, gtClipboard, $ */

glowroot.controller('ConfigInstrumentationListCtrl', [
  '$scope',
  '$location',
  '$http',
  '$timeout',
  '$q',
  'locationChanges',
  'queryStrings',
  'httpErrors',
  'modals',
  'instrumentationExport',
  function ($scope, $location, $http, $timeout, $q, locationChanges, queryStrings, httpErrors, modals, instrumentationExport) {

    if ($scope.hideMainContent()) {
      return;
    }

    $scope.display = function (config) {
      return config.className + '::' + config.methodName;
    };

    $scope.displayExtra = function (config) {
      var captureKind = config.captureKind;
      if (captureKind === 'timer') {
        return 'Timer';
      } else if (captureKind === 'trace-entry') {
        return 'Trace entry';
      } else if (captureKind === 'transaction') {
        return 'Transaction';
      } else {
        return 'Other';
      }
    };

    $scope.instrumentationQueryString = function (config) {
      var query = {};
      if ($scope.agentId) {
        query.agentId = $scope.agentId;
      }
      query.v = config.version;
      return queryStrings.encodeObject(query);
    };


    $scope.newQueryString = function () {
      if ($scope.agentId) {
        return '?agent-id=' + encodeURIComponent($scope.agentId) + '&new';
      }
      return '?new';
    };

    function refresh(deferred) {
      $http.get('backend/config/instrumentation?agent-id=' + encodeURIComponent($scope.agentId))
          .success(function (data) {
            $scope.loaded = true;
            $scope.configs = data.configs;
            // use object so dirty flag can be updated by child controllers
            $scope.dirty = data.jvmOutOfSync;
            $scope.jvmRetransformClassesSupported = data.jvmRetransformClassesSupported;
            var configs = angular.copy($scope.configs);
            angular.forEach(configs, function (config) {
              instrumentationExport.clean(config);
            });
            $scope.jsonExport = JSON.stringify(configs, null, 2);
            dealWithModals();
            if (deferred) {
              deferred.resolve();
            } else {
              // preload cache for class name and method name auto completion
              $http.get('backend/config/preload-classpath-cache?agent-id=' + encodeURIComponent($scope.agentId));
            }
          })
          .error(httpErrors.handler($scope, deferred));
    }

    $scope.displayImportModal = function () {
      $location.search('import', true);
    };

    $scope.displayExportModal = function () {
      $location.search('export', true);
    };

    $scope.deleteAll = function (deferred) {
      var postData = {
        agentId: $scope.agentId,
        versions: []
      };
      angular.forEach($scope.configs, function (config) {
        postData.versions.push(config.version);
      });
      $http.post('backend/config/instrumentation/remove', postData)
          .success(function () {
            deferred.resolve('Deleted');
            $scope.configs = [];
          })
          .error(httpErrors.handler($scope, deferred));
    };

    $scope.$watch('jsonToImport', function () {
      $scope.importErrorMessage = '';
    });

    $scope.importFromJson = function () {
      $scope.importErrorMessage = '';
      var configs;
      try {
        configs = angular.fromJson($scope.jsonToImport);
      } catch (e) {
        $scope.importErrorMessage = 'Invalid json';
        return;
      }
      if (!angular.isArray(configs)) {
        configs = [configs];
      }
      var initialErrors = [];
      var postData = {
        agentId: $scope.agentId,
        configs: []
      };
      angular.forEach(configs, function (config) {
        if (config.className === undefined) {
          initialErrors.push('Missing className');
        }
        if (config.methodName === undefined) {
          initialErrors.push('Missing methodName');
        }
        if (config.captureKind === undefined) {
          initialErrors.push('Missing captureKind');
        }
        if (initialErrors.length) {
          return;
        }
        var base = {
          classAnnotation: '',
          methodDeclaringClassName: '',
          methodAnnotation: '',
          methodReturnType: '',
          nestingGroup: '',
          priority: 0,
          transactionType: '',
          transactionNameTemplate: '',
          transactionUserTemplate: '',
          traceEntryCaptureSelfNested: false,
          enabledProperty: '',
          traceEntryEnabledProperty: ''
        };
        angular.extend(base, config);
        postData.configs.push(base);
      });
      if (initialErrors.length) {
        $scope.importErrorMessage = initialErrors.join(', ');
        return;
      }
      $scope.importing = true;
      $http.post('backend/config/instrumentation/import', postData)
          .success(function (data) {
            var deferred = $q.defer();
            deferred.promise.finally(function () {
              // leave spinner going until subsequent refresh is complete
              $scope.importing = false;
              $('#importModal').modal('hide');
            });
            refresh(deferred);
          })
          .error(function (data, status) {
            $scope.importing = false;
            httpErrors.handler($scope)(data, status);
          });
    };

    $scope.retransformClasses = function (deferred) {
      var postData = {
        agentId: $scope.agentId
      };
      $http.post('backend/admin/reweave', postData)
          .success(function (data) {
            $scope.dirty = false;
            if (data.classes) {
              var msg = 're-transformed ' + data.classes + ' class' + (data.classes > 1 ? 'es' : '');
              deferred.resolve('Success (' + msg + ')');
            } else {
              deferred.resolve('Success (no classes needed re-transforming)');
            }
          })
          .error(httpErrors.handler($scope, deferred));
    };

    function dealWithModals() {
      if ($location.search().import) {
        $scope.jsonToImport = '';
        $scope.importErrorMessage = '';
        $('#importModal').data('location-query', 'import');
        modals.display('#importModal', true);
        $timeout(function () {
          $('#importModal textarea').focus();
        });
      } else {
        $('#importModal').modal('hide');
      }
      if ($location.search().export) {
        gtClipboard('#exportModal .fa-clipboard', function () {
          return document.getElementById('jsonExport');
        }, function () {
          return $scope.jsonExport;
        });
        $('#exportModal').data('location-query', 'export');
        modals.display('#exportModal', true);
      } else {
        $('#exportModal').modal('hide');
      }
    }

    locationChanges.on($scope, function () {
      if (!$scope.loaded) {
        refresh();
        return;
      }
      dealWithModals();
    });
  }
]);
