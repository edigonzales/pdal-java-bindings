/*
 * Minimal C ABI shim around the PDAL C++ pipeline API.
 *
 * See pdal_ffi.h for the contract. The implementation keeps a single
 * PipelineManager plus its captured log/metadata/preview state per handle.
 */
#include "pdal_ffi.h"

#include <pdal/DimUtil.hpp>
#include <pdal/Log.hpp>
#include <pdal/PDALUtils.hpp>
#include <pdal/PipelineManager.hpp>
#include <pdal/PointLayout.hpp>
#include <pdal/PointTable.hpp>
#include <pdal/PointView.hpp>
#include <pdal/QuickInfo.hpp>
#include <pdal/SpatialReference.hpp>
#include <pdal/pdal_config.hpp>
#include <pdal/pdal_export.hpp>
#include <pdal/util/Bounds.hpp>

#include <cstdint>
#include <cstdlib>
#include <exception>
#include <memory>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

namespace {

struct PipelineHandle {
    std::unique_ptr<pdal::PipelineManager> manager;
    pdal::LogPtr log;
    std::ostringstream logStream;
    std::string logText;
    std::string metadata;
    std::string error;
    std::string json;
    uint64_t pointCount = 0;
    bool executed = false;

    bool previewed = false;
    uint64_t previewPointCount = 0;
    bool previewBoundsValid = false;
    double previewBounds[6] = {0.0, 0.0, 0.0, 0.0, 0.0, 0.0};
    std::string previewSrsWkt;
    std::string previewSrsAuthority;
    std::vector<std::pair<std::string, std::string>> previewDimensions;

    std::vector<pdal::PointViewPtr> views;
    std::vector<std::vector<std::pair<std::string, std::string>>> viewDimensions;
    bool viewsExecuted = false;
};

pdal::PointViewPtr viewAt(PipelineHandle* handle, int32_t view) {
    if (handle == nullptr || !handle->viewsExecuted || view < 0
        || static_cast<size_t>(view) >= handle->views.size()) {
        return nullptr;
    }
    return handle->views[static_cast<size_t>(view)];
}

pdal::Dimension::Id dimensionAt(const pdal::PointViewPtr& view, const char* name) {
    if (name == nullptr) {
        return pdal::Dimension::Id::Unknown;
    }
    return view->layout()->findDim(std::string(name));
}

std::string exceptionMessage(const std::exception& e) {
    const char* what = e.what();
    return what == nullptr ? std::string("unknown PDAL error") : std::string(what);
}

const char* dimTypeName(pdal::Dimension::Type type) {
    switch (type) {
    case pdal::Dimension::Type::Signed8:
        return "INT8";
    case pdal::Dimension::Type::Unsigned8:
        return "UINT8";
    case pdal::Dimension::Type::Signed16:
        return "INT16";
    case pdal::Dimension::Type::Unsigned16:
        return "UINT16";
    case pdal::Dimension::Type::Signed32:
        return "INT32";
    case pdal::Dimension::Type::Unsigned32:
        return "UINT32";
    case pdal::Dimension::Type::Signed64:
        return "INT64";
    case pdal::Dimension::Type::Unsigned64:
        return "UINT64";
    case pdal::Dimension::Type::Float:
        return "FLOAT32";
    case pdal::Dimension::Type::Double:
        return "FLOAT64";
    default:
        return "UNKNOWN";
    }
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
            handle->json = json;
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

int32_t pdal_ffi_pipeline_preview(void* pipeline) {
    auto* handle = static_cast<PipelineHandle*>(pipeline);
    if (handle == nullptr) {
        return 1;
    }
    if (!handle->error.empty()) {
        return 1;
    }
    try {
        pdal::QuickInfo info = handle->manager->preview();
        handle->previewPointCount = info.m_pointCount;
        if (info.m_bounds.valid()) {
            handle->previewBounds[0] = info.m_bounds.minx;
            handle->previewBounds[1] = info.m_bounds.miny;
            handle->previewBounds[2] = info.m_bounds.minz;
            handle->previewBounds[3] = info.m_bounds.maxx;
            handle->previewBounds[4] = info.m_bounds.maxy;
            handle->previewBounds[5] = info.m_bounds.maxz;
            handle->previewBoundsValid = true;
        }
        if (info.m_srs.valid()) {
            handle->previewSrsWkt = info.m_srs.getWKT();
            std::string code = info.m_srs.identifyHorizontalEPSG();
            if (!code.empty()) {
                handle->previewSrsAuthority = "EPSG:" + code;
            }
        }
        handle->previewDimensions.clear();
        // QuickInfo carries dimension names. Types require a prepared layout,
        // so a scratch manager is prepared from the same JSON. This keeps the
        // handle usable for a later execute() call.
        bool dimensionsResolved = false;
        try {
            pdal::PipelineManager scratch;
            std::istringstream scratchInput(handle->json);
            scratch.readPipeline(scratchInput);
            scratch.prepare();
            pdal::PointLayoutPtr layout = scratch.pointTable().layout();
            for (pdal::Dimension::Id id : layout->dims()) {
                handle->previewDimensions.emplace_back(
                    layout->dimName(id), std::string(dimTypeName(layout->dimType(id))));
            }
            dimensionsResolved = !handle->previewDimensions.empty();
        } catch (const std::exception&) {
            handle->previewDimensions.clear();
        } catch (...) {
            handle->previewDimensions.clear();
        }
        if (!dimensionsResolved) {
            for (const std::string& name : info.m_dimNames) {
                handle->previewDimensions.emplace_back(name, std::string("UNKNOWN"));
            }
        }
        handle->previewed = true;
        return 0;
    } catch (const std::exception& e) {
        handle->error = exceptionMessage(e);
        return 1;
    } catch (...) {
        handle->error = "unknown error while previewing pipeline";
        return 1;
    }
}

uint64_t pdal_ffi_preview_point_count(void* pipeline) {
    auto* handle = static_cast<PipelineHandle*>(pipeline);
    if (handle == nullptr || !handle->previewed) {
        return 0;
    }
    return handle->previewPointCount;
}

const double* pdal_ffi_preview_bounds(void* pipeline) {
    auto* handle = static_cast<PipelineHandle*>(pipeline);
    if (handle == nullptr || !handle->previewed || !handle->previewBoundsValid) {
        return nullptr;
    }
    return handle->previewBounds;
}

const char* pdal_ffi_preview_srs_wkt(void* pipeline) {
    auto* handle = static_cast<PipelineHandle*>(pipeline);
    if (handle == nullptr || !handle->previewed) {
        return "";
    }
    return handle->previewSrsWkt.c_str();
}

const char* pdal_ffi_preview_srs_authority(void* pipeline) {
    auto* handle = static_cast<PipelineHandle*>(pipeline);
    if (handle == nullptr || !handle->previewed) {
        return "";
    }
    return handle->previewSrsAuthority.c_str();
}

int32_t pdal_ffi_preview_dimension_count(void* pipeline) {
    auto* handle = static_cast<PipelineHandle*>(pipeline);
    if (handle == nullptr || !handle->previewed) {
        return 0;
    }
    return static_cast<int32_t>(handle->previewDimensions.size());
}

const char* pdal_ffi_preview_dimension_name(void* pipeline, int32_t index) {
    auto* handle = static_cast<PipelineHandle*>(pipeline);
    if (handle == nullptr || !handle->previewed || index < 0
        || static_cast<size_t>(index) >= handle->previewDimensions.size()) {
        return nullptr;
    }
    return handle->previewDimensions[static_cast<size_t>(index)].first.c_str();
}

const char* pdal_ffi_preview_dimension_type(void* pipeline, int32_t index) {
    auto* handle = static_cast<PipelineHandle*>(pipeline);
    if (handle == nullptr || !handle->previewed || index < 0
        || static_cast<size_t>(index) >= handle->previewDimensions.size()) {
        return nullptr;
    }
    return handle->previewDimensions[static_cast<size_t>(index)].second.c_str();
}

uint64_t pdal_ffi_pipeline_point_count(void* pipeline) {
    auto* handle = static_cast<PipelineHandle*>(pipeline);
    if (handle == nullptr || !handle->executed) {
        return 0;
    }
    return handle->pointCount;
}

int32_t pdal_ffi_pipeline_execute_view(void* pipeline) {
    auto* handle = static_cast<PipelineHandle*>(pipeline);
    if (handle == nullptr) {
        return 1;
    }
    if (!handle->error.empty()) {
        return 1;
    }
    try {
        handle->manager->execute(pdal::ExecMode::Standard);
        const pdal::PointViewSet& viewSet = handle->manager->views();
        handle->views.assign(viewSet.begin(), viewSet.end());
        handle->pointCount = 0;
        handle->viewDimensions.clear();
        for (const pdal::PointViewPtr& view : handle->views) {
            handle->pointCount += view->size();
            std::vector<std::pair<std::string, std::string>> dimensions;
            pdal::PointLayoutPtr layout = view->layout();
            for (pdal::Dimension::Id id : layout->dims()) {
                dimensions.emplace_back(
                    layout->dimName(id), std::string(dimTypeName(layout->dimType(id))));
            }
            handle->viewDimensions.push_back(std::move(dimensions));
        }
        handle->viewsExecuted = true;
        handle->executed = true;
        return 0;
    } catch (const std::exception& e) {
        handle->error = exceptionMessage(e);
        return 1;
    } catch (...) {
        handle->error = "unknown error while executing pipeline for point access";
        return 1;
    }
}

int32_t pdal_ffi_pipeline_view_count(void* pipeline) {
    auto* handle = static_cast<PipelineHandle*>(pipeline);
    if (handle == nullptr || !handle->viewsExecuted) {
        return 0;
    }
    return static_cast<int32_t>(handle->views.size());
}

uint64_t pdal_ffi_view_point_count(void* pipeline, int32_t view) {
    pdal::PointViewPtr pointView = viewAt(static_cast<PipelineHandle*>(pipeline), view);
    if (pointView == nullptr) {
        return 0;
    }
    return pointView->size();
}

int32_t pdal_ffi_view_dimension_count(void* pipeline, int32_t view) {
    auto* handle = static_cast<PipelineHandle*>(pipeline);
    if (handle == nullptr || !handle->viewsExecuted || view < 0
        || static_cast<size_t>(view) >= handle->viewDimensions.size()) {
        return 0;
    }
    return static_cast<int32_t>(handle->viewDimensions[static_cast<size_t>(view)].size());
}

const char* pdal_ffi_view_dimension_name(void* pipeline, int32_t view, int32_t index) {
    auto* handle = static_cast<PipelineHandle*>(pipeline);
    if (handle == nullptr || !handle->viewsExecuted || view < 0
        || static_cast<size_t>(view) >= handle->viewDimensions.size()) {
        return nullptr;
    }
    const auto& dimensions = handle->viewDimensions[static_cast<size_t>(view)];
    if (index < 0 || static_cast<size_t>(index) >= dimensions.size()) {
        return nullptr;
    }
    return dimensions[static_cast<size_t>(index)].first.c_str();
}

const char* pdal_ffi_view_dimension_type(void* pipeline, int32_t view, int32_t index) {
    auto* handle = static_cast<PipelineHandle*>(pipeline);
    if (handle == nullptr || !handle->viewsExecuted || view < 0
        || static_cast<size_t>(view) >= handle->viewDimensions.size()) {
        return nullptr;
    }
    const auto& dimensions = handle->viewDimensions[static_cast<size_t>(view)];
    if (index < 0 || static_cast<size_t>(index) >= dimensions.size()) {
        return nullptr;
    }
    return dimensions[static_cast<size_t>(index)].second.c_str();
}

int32_t pdal_ffi_view_read_double(
    void* pipeline, int32_t view, const char* dimension, uint64_t start, uint64_t count,
    double* target) {
    pdal::PointViewPtr pointView = viewAt(static_cast<PipelineHandle*>(pipeline), view);
    if (pointView == nullptr || target == nullptr) {
        return 1;
    }
    pdal::Dimension::Id id = dimensionAt(pointView, dimension);
    if (id == pdal::Dimension::Id::Unknown) {
        return 2;
    }
    if (start > pointView->size() || count > pointView->size() - start) {
        return 3;
    }
    for (uint64_t i = 0; i < count; ++i) {
        target[i] = pointView->getFieldAs<double>(id, start + i);
    }
    return 0;
}

int32_t pdal_ffi_view_read_int64(
    void* pipeline, int32_t view, const char* dimension, uint64_t start, uint64_t count,
    int64_t* target) {
    pdal::PointViewPtr pointView = viewAt(static_cast<PipelineHandle*>(pipeline), view);
    if (pointView == nullptr || target == nullptr) {
        return 1;
    }
    pdal::Dimension::Id id = dimensionAt(pointView, dimension);
    if (id == pdal::Dimension::Id::Unknown) {
        return 2;
    }
    if (start > pointView->size() || count > pointView->size() - start) {
        return 3;
    }
    for (uint64_t i = 0; i < count; ++i) {
        target[i] = pointView->getFieldAs<int64_t>(id, start + i);
    }
    return 0;
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
