#pragma once

#include "CoreMinimal.h"
#include "HAL/CriticalSection.h"
#include "Templates/Atomic.h"

enum class EEspeonProcessorState : uint8
{
	Unknown,
	Checking,
	Ready,
	NotInstalled,
	Problems,
	Installing,
	InstallFailed
};

/** Thread-safe snapshot for the UI. */
struct FEspeonProcessorStatus
{
	EEspeonProcessorState State = EEspeonProcessorState::Unknown;
	FString Text;            // one-line status, e.g. "Ready (GPU: NVIDIA GeForce RTX 5070)"
	float Progress = 0.f;    // 0..1 while installing
	FString StepMessage;     // current setup step message
};

/**
 * Detects whether the processor venv works (`python -m espeon_processor doctor`) and runs
 * processor/tools/setup_processor.ps1 to install or repair it. Both run on thread-pool threads.
 * Setup output goes to <Project>/Saved/EspeonAnim/setup.log.
 */
class ESPEONANIMEDITOR_API FEspeonProcessorSetup
{
public:
	static FEspeonProcessorSetup& Get();

	/** Runs doctor in the background (ignored while a check or install is running). */
	void CheckAsync();

	/** Runs the setup script in the background and re-checks at the end. The caller must have shown the license dialog. */
	bool StartInstall(FString& OutError);

	bool IsInstalling() const { return bInstalling; }
	FEspeonProcessorStatus GetStatus() const;

	/** Text of the confirmation dialog (model licenses and download size). */
	static FText GetLicenseNotice();
	static FString GetSetupLogPath();

private:
	void RunDoctor();
	void RunInstall(FString Script, FString ProcessorDir);
	void SetStatus(TFunctionRef<void(FEspeonProcessorStatus&)> Fn);

	mutable FCriticalSection Lock;
	FEspeonProcessorStatus Status;
	TAtomic<bool> bInstalling{ false };
	TAtomic<bool> bChecking{ false };
};
