#pragma once

#include "Commandlets/Commandlet.h"
#include "EspeonCommandlets.generated.h"

/** -run=EspeonDumpSkeleton -Skeleton=<asset path> -Out=<json path> [-Name=<profile name>] */
UCLASS()
class UEspeonDumpSkeletonCommandlet : public UCommandlet
{
	GENERATED_BODY()
public:
	UEspeonDumpSkeletonCommandlet();
	virtual int32 Main(const FString& Params) override;
};

/** -run=EspeonImportMotion -Motion=<json> -Skeleton=<asset> -Dest=<package path> -Name=<asset> [-Fbx=<path>] */
UCLASS()
class UEspeonImportMotionCommandlet : public UCommandlet
{
	GENERATED_BODY()
public:
	UEspeonImportMotionCommandlet();
	virtual int32 Main(const FString& Params) override;
};
