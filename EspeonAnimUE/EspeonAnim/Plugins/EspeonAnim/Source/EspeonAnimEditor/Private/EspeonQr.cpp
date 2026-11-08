#include "EspeonQr.h"
#include "Engine/Texture2D.h"
#include "TextureResource.h"

THIRD_PARTY_INCLUDES_START
#include "ThirdParty/QrCodeGenerator/qrcodegen.hpp"
THIRD_PARTY_INCLUDES_END

bool FEspeonQr::EncodePixels(const FString& Text, int32 ModuleScale, int32 Border, int32& OutSize, TArray<uint8>& OutBgra, FString& OutError)
{
	if (Text.IsEmpty() || ModuleScale < 1 || Border < 0)
	{
		OutError = TEXT("Invalid QR parameters");
		return false;
	}
	try
	{
		FTCHARToUTF8 Utf8(*Text);
		const std::string Str(Utf8.Get(), Utf8.Length());
		const qrcodegen::QrCode Qr = qrcodegen::QrCode::encodeText(Str.c_str(), qrcodegen::QrCode::Ecc::MEDIUM);
		const int32 N = Qr.getSize();
		OutSize = (N + 2 * Border) * ModuleScale;
		OutBgra.SetNumUninitialized(OutSize * OutSize * 4);
		for (int32 Y = 0; Y < OutSize; ++Y)
		{
			for (int32 X = 0; X < OutSize; ++X)
			{
				const int32 MX = X / ModuleScale - Border;
				const int32 MY = Y / ModuleScale - Border;
				const bool bDark = MX >= 0 && MY >= 0 && MX < N && MY < N && Qr.getModule(MX, MY);
				const uint8 V = bDark ? 0 : 255;
				uint8* P = &OutBgra[(Y * OutSize + X) * 4];
				P[0] = V; P[1] = V; P[2] = V; P[3] = 255;
			}
		}
		return true;
	}
	catch (const std::exception& E)
	{
		OutError = FString::Printf(TEXT("QR encoding failed: %s"), UTF8_TO_TCHAR(E.what()));
		return false;
	}
}

UTexture2D* FEspeonQr::CreateTexture(const FString& Text, FString& OutError, int32 ModuleScale, int32 Border)
{
	int32 Size = 0;
	TArray<uint8> Pixels;
	if (!EncodePixels(Text, ModuleScale, Border, Size, Pixels, OutError)) return nullptr;
	UTexture2D* Tex = UTexture2D::CreateTransient(Size, Size, PF_B8G8R8A8, NAME_None, TConstArrayView64<uint8>(Pixels));
	if (!Tex)
	{
		OutError = TEXT("Could not create the QR texture");
		return nullptr;
	}
	Tex->Filter = TF_Nearest;
	Tex->SRGB = false;
	Tex->UpdateResource();
	return Tex;
}
