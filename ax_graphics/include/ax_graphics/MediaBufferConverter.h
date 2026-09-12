/*
 * Copyright 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma once

#include <android/hardware_buffer.h>
#include <system/graphics.h>
#include <cstdint>

namespace axion::graphics {

class MediaBufferConverter final {
public:
    MediaBufferConverter() = delete;

    static bool isConversionEnabled();
    static bool isMediaOrHdrBuffer(const AHardwareBuffer_Desc& desc, int32_t dataspace = 0);
    static bool isMediaOrHdrBuffer(uint32_t format, uint64_t usage, int32_t dataspace = 0);
    static AHardwareBuffer* convertToRgba8888(AHardwareBuffer* srcBuffer,
                                             AHardwareBuffer* existingDst = nullptr);
};

}
