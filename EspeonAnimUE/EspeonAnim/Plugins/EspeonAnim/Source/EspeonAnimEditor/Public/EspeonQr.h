#pragma once

#include "CoreMinimal.h"

class UTexture2D;

/** QR code helper over the vendored Nayuki generator (Private/ThirdParty/QrCodeGenerator, MIT). */
struct ESPEONANIMEDITOR_API FEspeonQr
{
	/**
	 * Encodes Text (UTF-8, error correction M) into a black-on-white BGRA image.
	 * ModuleScale = pixels per QR module, Border = quiet-zone modules. OutSize is the image edge in pixels.
	 */
	static bool EncodePixels(const FString& Text, int32 ModuleScale, int32 Border, int32& OutSize, TArray<uint8>& OutBgra, FString& OutError);

	/** Transient, nearest-filtered texture of the QR code (game thread). */
	static UTexture2D* CreateTexture(const FString& Text, FString& OutError, int32 ModuleScale = 6, int32 Border = 4);
};
