/*
 * Copyright © 2019 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
*/

import React from 'react';
import { ContextMenu, IContextMenuOption } from 'components/ContextMenu';
import WranglerConnection from 'components/PipelineContextMenu/WranglerConnection';
import If from 'components/If';
import PropTypes from 'prop-types';
import { CopyFromClipBoard } from 'services/Clipboard';
import { objectQuery } from 'services/helpers';

async function isPasteOptionDisabled() {
  let clipText;
  try {
    clipText = await CopyFromClipBoard();
  } catch (e) {
    return true;
  }
  return isClipboardPastable(clipText);
}

function isClipboardPastable(text) {
  let jsonNodes;
  if (typeof text !== 'object') {
    try {
      jsonNodes = JSON.parse(text);
    } catch (e) {
      return true;
    }
  }

  return objectQuery(jsonNodes, 'stages', 'length') > 0 ? false : true;
}

export default function PipelineContextMenu({ onWranglerSourceAdd }) {
  const [showWranglerModal, setShowWranglerModal] = React.useState(false);
  const [pasteOptionDisabled, setPasteOptionDisabled] = React.useState(true);

  isPasteOptionDisabled().then(setPasteOptionDisabled);
  console.log('paste option:', pasteOptionDisabled);

  const menuOptions: IContextMenuOption[] = [
    {
      name: 'add-wrangler-source',
      label: 'Add a wrangler source',
      onClick: () => setShowWranglerModal(!showWranglerModal),
    },
    {
      name: 'pipeline-node-copy',
      label: 'Copy',
      onClick: () => console.log('Copying node(s)'),
      disabled: true,
    },
    {
      name: 'pipeline-node-paste',
      label: 'Paste',
      onClick: () => console.log('Pasting node(s)'),
      disabled: pasteOptionDisabled,
    },
  ];
  const onWranglerSourceAddWrapper = (...props) => {
    setShowWranglerModal(!showWranglerModal);
    onWranglerSourceAdd.apply(null, props);
  };
  return (
    <React.Fragment>
      <ContextMenu selector="#diagram-container" options={menuOptions} />
      <If condition={showWranglerModal}>
        <WranglerConnection
          onModalClose={() => setShowWranglerModal(!showWranglerModal)}
          onWranglerSourceAdd={onWranglerSourceAddWrapper}
        />
      </If>
    </React.Fragment>
  );
}

(PipelineContextMenu as any).propTypes = {
  onWranglerSourceAdd: PropTypes.func,
};
