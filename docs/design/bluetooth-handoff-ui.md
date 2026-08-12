# Bluetooth Handoff UI Contract

The Bluetooth card is a device catalog, not a view filtered to one selected computer.

## Grouping and labels

- Show every visible paired Bluetooth device by default. The headset-only filter is optional.
- Use exactly two sections: connected and disconnected. A non-null `activeEndpointId` is the
  connection truth; the selected computer does not change section membership.
- A connected row includes its endpoint name. A disconnected row shows only the Bluetooth device
  name.
- Identity and merging use normalized Bluetooth addresses. Display names are not identifiers.

## Actions

- `Switch to this device` targets the local Android endpoint.
- `Switch to other device` lists every endpoint except the local endpoint and the current active
  endpoint. Explicitly unsupported targets remain visible but disabled with an explanation.
- `Disconnect` is enabled only when an active endpoint is known and targets that endpoint.
- An empty endpoint list is the backward-compatible legacy meaning of “all endpoints”.
- An accepted privileged Bluetooth call is not success. Wait for the requested state with a
  bounded timeout and surface the final result.

## Verification

- `BluetoothHandoffUiPolicyTest` covers grouping, filtering, endpoint exclusion, and legacy
  endpoint support.
- Physical checks may read catalogs without switching a headset. Actual switch testing must
  preserve the intended final radio and endpoint state.
