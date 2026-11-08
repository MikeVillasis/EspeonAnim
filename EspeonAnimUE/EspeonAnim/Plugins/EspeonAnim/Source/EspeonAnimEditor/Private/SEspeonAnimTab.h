#pragma once

#include "CoreMinimal.h"
#include "EspeonJobManager.h"
#include "Styling/SlateBrush.h"
#include "UObject/StrongObjectPtr.h"
#include "Widgets/DeclarativeSyntaxSupport.h"
#include "Widgets/SCompoundWidget.h"

class UTexture2D;
class ITableRow;
class STableViewBase;
class STextBlock;
template <typename ItemType> class SListView;
template <typename OptionType> class SComboBox;

/** Content of the "EspeonAnim Capture" nomad tab: receiver toggle, pairing QR, manual import and the job list. */
class SEspeonAnimTab : public SCompoundWidget
{
public:
	SLATE_BEGIN_ARGS(SEspeonAnimTab) {}
	SLATE_END_ARGS()

	void Construct(const FArguments& InArgs);
	virtual ~SEspeonAnimTab() override;

private:
	typedef TSharedPtr<FEspeonJobStatus> FRowPtr;

	void ToggleReceiver();
	void StartReceiver();
	void RefreshAddresses();
	void RebuildQr();
	void NewPairingCode();
	void ImportFile();
	void InstallProcessor();
	EActiveTimerReturnType TickRefresh(double InCurrentTime, float InDeltaTime);
	TSharedRef<ITableRow> GenerateRow(FRowPtr Item, const TSharedRef<STableViewBase>& Owner);
	FString CurrentHost() const;
	FText GetAddressText() const;

	TSharedPtr<SListView<FRowPtr>> ListView;
	TSharedPtr<SComboBox<TSharedPtr<FString>>> AddressCombo;
	TArray<TSharedPtr<FString>> Addresses;
	TSharedPtr<FString> SelectedAddress;
	TArray<FRowPtr> Rows;
	TSharedPtr<FSlateBrush> QrBrush;
	TStrongObjectPtr<UTexture2D> QrTexture;
	FString PairUrl;
	FString LastError;
	FString LastSignature;
};
