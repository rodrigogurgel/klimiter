## [unreleased]

### 🚀 Features

- *(core)* Add initial rate limiter implementation
- *(core)* Stabilize rate limiting workflow
- *(core)* Improve core module structure
- *(redis)* Add Redis backend module
- *(load-test)* Add --save-responses flag to capture full gRPC responses

### 🐛 Bug Fixes

- *(core)* Fix rate limit coordinator behavior
- *(redis)* Make gracePeriod configurable for Redis key TTL

### 💼 Other

- *(project)* Add Gradle version catalog

### 🚜 Refactor

- *(project)* Improve package and module structure
- *(service)* Reorganize service and load test modules

### 📚 Documentation

- *(project)* Add documentation and task automation files
- *(project)* Update project documentation
- Update changelog
- Add comprehensive documentation and adjust example domain config
- Document --save-responses flag and configurable Redis gracePeriod

### 🧪 Testing

- *(architecture)* Add Konsist architecture tests
- *(project)* Improve code coverage

### ⚙️ Miscellaneous Tasks

- *(project)* Update gitignore and rename taskfile
- Add git-cliff changelog automation
