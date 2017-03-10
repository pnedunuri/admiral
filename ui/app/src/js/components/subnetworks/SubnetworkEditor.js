/*
 * Copyright (c) 2017 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

import SubnetworkEditorVue from 'components/subnetworks/SubnetworkEditorVue.html';
import { SubnetworksActions } from 'actions/Actions';
import services from 'core/services';
import utils from 'core/utils';

export default Vue.component('subnetwork-editor', {
  template: SubnetworkEditorVue,
  props: {
    endpointLink: {
      required: true,
      type: String
    },
    model: {
      required: true,
      type: Object
    }
  },
  computed: {
    validationErrors() {
      return (this.model.validationErrors && this.model.validationErrors._generic);
    }
  },
  data() {
    return {
      network: this.model.item.network,
      name: this.model.item.name,
      cidr: this.model.item.subnetCIDR,
      supportPublicIpAddress: this.model.item.supportPublicIpAddress,
      defaultForZone: this.model.item.defaultForZone,
      tags: this.model.item.tags && this.model.item.tags.asMutable() || [],
      saveDisabled: !this.model.item.documentSelfLink
    };
  },
  methods: {
    cancel($event) {
      $event.stopImmediatePropagation();
      $event.preventDefault();
      SubnetworksActions.cancelEditSubnetwork();
    },
    save($event) {
      $event.stopImmediatePropagation();
      $event.preventDefault();
      let toSave = this.getModel();
      var tagRequest = utils.createTagAssignmentRequest(toSave.documentSelfLink,
          this.model.item.tags || [], this.tags);
      if (toSave.documentSelfLink) {
        SubnetworksActions.updateSubnetwork(toSave, tagRequest);
      } else {
        SubnetworksActions.createSubnetwork(toSave, tagRequest);
      }
    },
    searchNetworks(...args) {
      return new Promise((resolve, reject) => {
        services.searchNetworks.apply(null,
            [this.endpointLink, ...args]).then((result) => {
          resolve(result);
        }).catch(reject);
      });
    },
    onNetworkChange(network) {
      this.network = network;
    },
    onNameChange(name) {
      this.name = name;
      this.saveDisabled = this.isSaveDisabled();
    },
    onCidrChange(cidr) {
      this.cidr = cidr;
      this.saveDisabled = this.isSaveDisabled();
    },
    onSupportPublicIpAddressChange(supportPublicIpAddress) {
      this.supportPublicIpAddress = supportPublicIpAddress;
    },
    onDefaultForZoneChange(defaultForZone) {
      this.defaultForZone = defaultForZone;
    },
    onTagsChange(tags) {
      this.tags = tags;
    },
    isSaveDisabled() {
      return !this.network || !this.name || !this.cidr;
    },
    getModel() {
      return $.extend({}, this.model.item, {
        endpointLink: this.endpointLink,
        networkLink: this.network.documentSelfLink,
        name: this.name,
        defaultForZone: this.defaultForZone,
        subnetCIDR: this.cidr,
        supportPublicIpAddress: this.supportPublicIpAddress,
        tagLinks: undefined
      });
    }
  }
});
