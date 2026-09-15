/*
 * Minimal C ABI shim around the PDAL C++ pipeline API.
 *
 * See pdal_ffi.h for the contract. The implementation keeps a single
 * PipelineManager plus its captured log/metadata/error state per handle.
 */
#include "pdal_ffi.h"

#include <pdal/Log.hpp>
#include <pdal/PDALUtils.hpp>
#include <pdal/PipelineManager.hpp>
#include <pdal/pdal_config.hpp>
#include <pdal/pdal_export.hpp>

#include <cstdint>
#include <cstdlib>
#include <exception>
#include <memory>
#include <sstream>
#include <string>

namespace {

struct PipelineHandle {
    std::unique_ptr<pdal::PipelineManager> manager;
    pdal::LogPtr log;
    std::ostringstream logStream;
    std::string logText;
    std::string metadata;
    std::string error;
    uint64_t pointCount = 0;
    bool executed = false;
};

std::string exceptionMessage(const std::exception& e) {
    const char* what = e.what();
    return what == nullptr ? std::string("unknown PDAL error") : std::string(what);
}

} // namespace

extern "C" {

const char* pdal_ffi_version(void) {
    static const std::string version = pdal::Config::versionString();
    return version.c_str();
}

int32_t pdal_ffi_set_environment(const char* name, const char* value) {
    if (name == nullptr || value == nullptr) {
        return 1;
    }
#if defined(_WIN32)
    return _putenv_s(name, value) == 0 ? 0 : 1;
#else
    return setenv(name, value, 1) == 0 ? 0 : 1;
#endif
}

void* pdal_ffi_pipeline_create(const char* json) {
    auto handle = std::make_unique<PipelineHandle>();
    try {
        handle->log = pdal::Log::makeLog("pdal-ffi", &handle->logStream);
        handle->manager = std::make_unique<pdal::PipelineManager>();
        handle->manager->setLog(handle->log);
        if (json == nullptr) {
            handle->error = "pipeline JSON must not be null";
        } else {
            std::istringstream input(json);
            handle->manager->readPipeline(input);
        }
    } catch (const std::exception& e) {
        handle->error = exceptionMessage(e);
    } catch (...) {
        handle->error = "unknown error while reading pipeline";
    }
    return handle.release();
}

int32_t pdal_ffi_pipeline_execute(void* pipeline) {
    auto* handle = static_cast<PipelineHandle*>(pipeline);
    if (handle == nullptr) {
        return 1;
    }
    if (!handle->error.empty()) {
        return 1;
    }
    try {
        handle->metadata.clear();
        handle->pointCount = handle->manager->execute();
        handle->executed = true;
        return 0;
    } catch (const std::exception& e) {
        handle->error = exceptionMessage(e);
        return 1;
    } catch (...) {
        handle->error = "unknown error while executing pipeline";
        return 1;
    }
}

uint64_t pdal_ffi_pipeline_point_count(void* pipeline) {
    auto* handle = static_cast<PipelineHandle*>(pipeline);
    if (handle == nullptr || !handle->executed) {
        return 0;
    }
    return handle->pointCount;
}

const char* pdal_ffi_pipeline_metadata(void* pipeline) {
    auto* handle = static_cast<PipelineHandle*>(pipeline);
    if (handle == nullptr || !handle->executed) {
        return "";
    }
    try {
        handle->metadata = pdal::Utils::toJSON(handle->manager->getMetadata());
    } catch (const std::exception& e) {
        handle->error = exceptionMessage(e);
        return "";
    } catch (...) {
        handle->error = "unknown error while reading pipeline metadata";
        return "";
    }
    return handle->metadata.c_str();
}

const char* pdal_ffi_pipeline_log(void* pipeline) {
    auto* handle = static_cast<PipelineHandle*>(pipeline);
    if (handle == nullptr) {
        return "";
    }
    handle->logText = handle->logStream.str();
    return handle->logText.c_str();
}

const char* pdal_ffi_pipeline_error(void* pipeline) {
    auto* handle = static_cast<PipelineHandle*>(pipeline);
    if (handle == nullptr) {
        return "";
    }
    return handle->error.c_str();
}

void pdal_ffi_pipeline_destroy(void* pipeline) {
    delete static_cast<PipelineHandle*>(pipeline);
}

} // extern "C"
