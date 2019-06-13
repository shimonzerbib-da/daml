-- Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

{-# LANGUAGE OverloadedStrings #-}

-- | Go to the definition of a variable.
module DA.Service.Daml.LanguageServer.Definition
    ( handle
    ) where

import           Development.IDE.LSP.Protocol
import Development.IDE.Types.Diagnostics

import qualified DA.Service.Daml.Compiler.Impl.Handle as Compiler
import qualified Development.IDE.Logger as Logger

import qualified Data.Text as T
import Data.Text.Prettyprint.Doc
import Data.Text.Prettyprint.Doc.Render.Text

-- | Go to the definition of a variable.
handle
    :: Logger.Handle
    -> Compiler.IdeState
    -> TextDocumentPositionParams
    -> IO LocationResponseParams
handle loggerH compilerH (TextDocumentPositionParams (TextDocumentIdentifier uri) pos) = do


    mbResult <- case uriToFilePath' uri of
        Just (toNormalizedFilePath -> filePath) -> do
          Logger.logInfo loggerH $
            "Definition request at position " <>
            renderStrict (layoutPretty defaultLayoutOptions $ prettyPosition pos) <>
            " in file: " <> T.pack (fromNormalizedFilePath filePath)
          Compiler.gotoDefinition compilerH filePath pos
        Nothing       -> pure Nothing

    case mbResult of
        Nothing ->
            pure $ MultiLoc []

        Just loc ->
            pure $ SingleLoc loc
